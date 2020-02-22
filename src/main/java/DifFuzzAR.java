import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.visitor.filter.TypeFilter;
import spoon.support.reflect.code.CtLiteralImpl;
import utils.VulnerableMethodUses;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

// TODO code simplification and beautification.
public class DifFuzzAR {
    private static Logger logger = LoggerFactory.getLogger(DifFuzzAR.class);
    private static boolean afterMemClear = false;

    public static void main(String[] args) {
        if (args.length != 1) {
            logger.warn("There needs to be at least one argument for the application.");
            return;
        }
        String driverPath = args[0];
        logger.info(String.format("The passed path was %s.", driverPath));

        Launcher launcher = new Launcher();
        launcher.addInputResource(driverPath);
        launcher.getEnvironment().setCommentEnabled(false); // Para que os comentários no código da Driver sejam ignorados
        CtModel model = launcher.buildModel();
        List<CtMethod> methodList = model.filterChildren(new TypeFilter<>(CtMethod.class)).list();

        if (methodList.size() == 0) {
            logger.warn("The file should contain at least the main method, and it contains no methods.");
            return;
        }

        CtMethod mainMethod = null;

        for (CtMethod method : methodList) {
            if (method.getSimpleName().equals("main")) {
                mainMethod = method;
                break;
            }
        }

        if (mainMethod == null) {
            logger.warn("The Driver provided did not contain a main method.");
            return;
        }

        String safeModeVariable = null;
        boolean safeMode = false;

        List<CtField> fieldList = model.filterChildren(new TypeFilter<>(CtField.class)).list();
        Optional<CtField> optionalSafeModeField = fieldList.stream()
                .filter(field -> field.isFinal() && field.getType().getSimpleName().equals("boolean"))
                .findAny();

        if (optionalSafeModeField.isPresent()) {
            CtField safeModeField = optionalSafeModeField.get();
            safeModeVariable = safeModeField.getSimpleName();
            safeMode = (boolean) ((CtLiteralImpl) safeModeField.getAssignment()).getValue();
        }

        Iterator<CtElement> iterator = mainMethod.getBody().iterator();

        VulnerableMethodUses vulnerableMethodUseCases = discoverMethod(iterator, safeMode, safeModeVariable);

        if (!vulnerableMethodUseCases.isValid()) {
            logger.warn("The tool could not discover the vulnerable method.");
            return;
        }

        // First Vulnerable method use case
        String firstVulnerableMethodName = vulnerableMethodUseCases.getFirstUseCaseMethodName();
        String[] firstVulnerableMethodArguments = vulnerableMethodUseCases.getFirstUseCaseArgumentsNames();

        logger.info(String.format("The first instance of the vulnerable method %s found had the following arguments %s.",
                firstVulnerableMethodName, Arrays.toString(firstVulnerableMethodArguments)));

        // Second Vulnerable method use case
        String secondVulnerableMethodName = vulnerableMethodUseCases.getSecondUseCaseMethodName();
        String[] secondVulnerableMethodArguments = vulnerableMethodUseCases.getSecondUseCaseArgumentsNames();

        logger.info(String.format("The second instance of the vulnerable method %s found had the following arguments %s.",
                secondVulnerableMethodName, Arrays.toString(secondVulnerableMethodArguments)));

        int idx = 0;
        for (; idx < firstVulnerableMethodArguments.length; idx++) {
            if (!firstVulnerableMethodArguments[idx].equals(secondVulnerableMethodArguments[idx]))
                break;
        }

        if (idx == firstVulnerableMethodArguments.length) {
            logger.warn(String.format("The vulnerable method %s is used in the Driver always with the same parameters.", firstVulnerableMethodName));
            return;
        }
        logger.info(String.format("The private parameter in the vulnerable method %s is in position %d", firstVulnerableMethodName, idx));
    }

    /**
     * Travels trough the AST of the main method, trying to find any of the patterns that indicates the presence of the
     * vulnerable method like it's presented in <a href="https://github.com/RuiDTLima/DifFuzzAR/issues/1">GitHub issue #1</a>.
     * TODO add the comments of examples where that pattern can be seen.
     * @param iterator An iterator of AST of the method where the vulnerable method is present.
     * @param safeMode  Indicates if in this method it is used the safe or unsafe variations of the vulnerable methods.
     * @param safeModeVariable  The name of the variable that indicates if the safeMode is in action.
     * @return The vulnerable method.
     */
    static VulnerableMethodUses discoverMethod(Iterator<CtElement> iterator, boolean safeMode, String safeModeVariable) {
        VulnerableMethodUses vulnerableMethodUses = new VulnerableMethodUses();

        while (iterator.hasNext()) {
            CtElement element = iterator.next();
            String codeLine = element.prettyprint();

            if (safeModeVariable != null && codeLine.contains("if") && codeLine.contains(safeModeVariable) && !(element instanceof CtTry)) {
                List<CtBlock> elements = element.getElements(new TypeFilter<>(CtBlock.class));
                int idx = 0;
                if (codeLine.contains("if(!")) {
                    if (safeMode)
                        idx = 1;
                    else idx = 0;
                } else {
                    if (safeMode)
                        idx = 0;
                    else idx = 1;
                }
                CtBlock ctBlock = elements.get(idx);


                Iterator ctBlockIterator = ctBlock.iterator();
                while (ctBlockIterator.hasNext()) {
                    CtStatement statement = (CtStatement) ctBlockIterator.next();
                    if (statement.toString().contains("Mem.clear()"))
                        afterMemClear = true;
                    else if (afterMemClear) {
                        afterMemClear = false;
                        String vulnerableMethodLine = statement.prettyprint();
                        logger.info(String.format("The line of code %s appears after the Mem.clear.", vulnerableMethodLine));
                        vulnerableMethodUses.setUseCase(vulnerableMethodLine);
                    }
                }
            } else if (codeLine.contains("Mem.clear()")) {
                afterMemClear = true;
                if (codeLine.contains("try")) {
                    List<CtBlock> elements = element.getElements(new TypeFilter<>(CtBlock.class));
                    CtBlock ctBlock = elements.get(0);  // the code inside try block
                    Iterator ctBlockIterator = ctBlock.iterator();

                    return discoverMethod(ctBlockIterator, safeMode, safeModeVariable);
                }
            } else if (validAfterMemClear(codeLine)) {
                if (codeLine.contains("try")) { // Example in themis_pac4j_safe
                    List<CtBlock> elements = element.getElements(new TypeFilter<>(CtBlock.class));
                    CtBlock ctBlock = elements.get(0);  // the code inside try block
                    Iterator ctBlockIterator = ctBlock.iterator();

                    VulnerableMethodUses tempVulnerableMethodUses = discoverMethod(ctBlockIterator, safeMode, safeModeVariable);
                    vulnerableMethodUses.addFromOtherVulnerableMethodUses(tempVulnerableMethodUses);
                    if (vulnerableMethodUses.isValid())
                        return vulnerableMethodUses;
                } else {
                    afterMemClear = false;
                    logger.info(String.format("The line of code %s appears after the Mem.clear.", codeLine));
                    String pretyElement = element.prettyprint();    // To remove the full name of the case in use, so that it contains only the class and method names.
                    vulnerableMethodUses.setUseCase(pretyElement);
                }
            }
        }
        return vulnerableMethodUses;
    }

    /**
     * Validates the instruction after a MemClear. That instruction can't be an assignment of a value to a variable other
     * than a method call, except for a object creation.
     * @param line
     * @return
     */
    private static boolean validAfterMemClear(String line) {
        return afterMemClear && !line.equals("") && !(
                line.contains("=") && (!line.contains("(") ||
                        (line.contains("(") && line.contains("new"))));
    }
}