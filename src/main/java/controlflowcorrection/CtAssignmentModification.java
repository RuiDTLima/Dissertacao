package controlflowcorrection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.code.*;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtLocalVariableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.support.reflect.code.*;
import util.ModifyOperationFunction;
import util.NamingConvention;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

class CtAssignmentModification {
    private static final Logger logger = LoggerFactory.getLogger(CtAssignmentModification.class);
    private static HashMap<Class<?>, ModifyOperationFunction<Factory, CtExpression<?>, Set<String>, CtExpression<?>>> modifyOperation;

    static {
        populateModifyOperation();
    }

    /**
     * Add all the methods available to modify operations to be used in the function pattern
     */
    private static void populateModifyOperation() {
        modifyOperation = new HashMap<>();
        modifyOperation.put(CtArrayReadImpl.class, 		CtArrayModification::modifyArrayOperation);
        modifyOperation.put(CtBinaryOperatorImpl.class, CtBinaryOperatorModification::modifyBinaryOperator);
        modifyOperation.put(CtFieldReadImpl.class, 		CtFieldReadModification::modifyFieldRead);
    }

    /**
     * This method is invoked when an assignment is found before finding an if or cycle block that uses a secret. So, here
     * no changes will be made to the operation, only check if it uses any secret, including the variables where a secret
     * was assigned, to add the variable assigned to the list of secrets.
     * @param statement 		The statement to be analysed.
     * @param factory   		The factory used to create new instructions.
     * @param secretVariables   The list of secret variables.
     * @param publicArguments   The list of public variables. NOT USED.
     * @return  				Returns a block with the same instruction in the first index and null in the second index.
     */
    static CtBlock<?>[] traverseStatement(CtStatement statement,
                                          Factory factory,
                                          List<CtVariable<?>> secretVariables,
                                          List<CtParameter<?>> publicArguments) {
        logger.info("Found an assignment while traversing the method.");

        CtAssignment<?, ?> assignmentStatement = (CtAssignment<?, ?>) statement;
        CtExpression<?> assignment = assignmentStatement.getAssignment();
        boolean usesSecret = ControlFlowBasedVulnerabilityCorrection.usesSecret(assignment.toString(), secretVariables);

        logger.info("Does assignment {} uses a secret? {}.", assignment.toString(), usesSecret);

        if (usesSecret) {
            CtExpression<?> assigned = assignmentStatement.getAssigned();
            CtVariable<?> secretVariable;

            if (assigned instanceof CtArrayWrite) {
                CtArrayWrite<?> arrayWrite = (CtArrayWrite<?>) assigned;
                CtVariableReadImpl<?> localVariableReference = (CtVariableReadImpl<?>) arrayWrite.getTarget();
                CtVariableReference<?> variable = localVariableReference.getVariable();
                secretVariable = factory.createLocalVariable(variable.getType(), variable.getSimpleName(), null);
                logger.info("The assignment is an array write where the target is now a secret variable");
            } else if (assigned instanceof CtVariableWrite) {
                CtVariableWrite<?> variable = (CtVariableWrite<?>) assigned;
                secretVariable = variable.getVariable().getDeclaration();
            } else {
                secretVariable = (CtVariable<?>) assigned;
                logger.info("The assignment is to a variable, that is now a secret variable.");
            }
            secretVariables.add(secretVariable);
        }

        CtBlock<?>[] returnBlocks = new CtBlock[2];
        returnBlocks[0] = new CtBlockImpl<>();
        returnBlocks[1] = new CtBlockImpl<>();
        returnBlocks[0].addStatement(assignmentStatement.clone());
        returnBlocks[1].addStatement(null);
        return returnBlocks;
    }

    /**
     * A method where the assignment is modified. The variable being assigned to will change, either to a previously created
     * variable or to a new variable created on the spot. The value being assigned might change depending on the type of
     * assignment.
     * @param element   			The assignment being modified. It is passed as an element so that the function
     *                              pattern can be used.
     * @param factory   			The factory used to create new instructions.
     * @param initialStatement  	The initial statement that represents the 'if' statement this operation belongs to.
     *                              Not used in this method, but it is passed to allow the use of the function pattern.
     *                              NOT USED.
     * @param dependableVariables   A set of the dependable variables. The variables used in the 'if' condition and all
     *                              variables where an assignment uses any of those variables. Meaning the variables that
     *                              can't be used outside the then statement.
     * @param secretVariables   	The list of all secret variables of the method. Not used in this method, but it is
     *                              passed to allow the use of the function pattern.
     * @return  					Returns in the first index the old assignment (the element passed) and in the second
     * 								index the new modified assignment.
     */
    static CtStatement[] modifyAssignment(CtElement element,
                                          Factory factory,
                                          CtIfImpl initialStatement,
                                          Set<String> dependableVariables,
                                          List<CtVariable<?>> secretVariables) {
        logger.info("Found an assignment to modify.");

        String newAssigned;
        CtAssignmentImpl<?, ?> assignmentImpl = (CtAssignmentImpl<?, ?>) element;
        CtAssignment<?, ?> oldAssignment = assignmentImpl.clone();
        CtTypeReference type = assignmentImpl.getType();
        CtExpression<?> assigned = assignmentImpl.getAssigned();
        CtExpression assignment = assignmentImpl.getAssignment();

        boolean usesSecret = ControlFlowBasedVulnerabilityCorrection.usesSecret(assignment.toString(), secretVariables);

        if (usesSecret) {
            updateSecretVariables(factory, secretVariables, assigned, assignment);
        }

        ModifyOperationFunction<Factory, CtExpression<?>, Set<String>, CtExpression<?>> function = modifyOperation.get(assignment.getClass());

        if (function != null) {
            assignment = function.apply(factory, assignment, dependableVariables);
        } else if (assignment instanceof CtConditionalImpl) {
            CtConditionalImpl<?> conditional = (CtConditionalImpl<?>) assignment;
            String condition = conditional.getCondition().toString();
            String thenExpression = conditional.getThenExpression().toString();
            String elseExpression = conditional.getElseExpression().toString();
            if (dependableVariables.contains(condition) ||
                    dependableVariables.contains(thenExpression) ||
                    dependableVariables.contains(elseExpression)) {

                dependableVariables.add(assigned.toString());
                return new CtStatement[]{oldAssignment, null};
            }
            if (ControlFlowBasedVulnerabilityCorrection.isKeyInVariablesReplacement(condition)) {
                String newCondition = ControlFlowBasedVulnerabilityCorrection.getValueVariablesReplacement(condition);
                conditional.setCondition(factory.createCodeSnippetExpression(newCondition));
            }
            if (ControlFlowBasedVulnerabilityCorrection.isKeyInVariablesReplacement(thenExpression)) {
                String newThenExpression = ControlFlowBasedVulnerabilityCorrection.getValueVariablesReplacement(thenExpression);
                conditional.setThenExpression(factory.createCodeSnippetExpression(newThenExpression));
            }
            if (ControlFlowBasedVulnerabilityCorrection.isKeyInVariablesReplacement(elseExpression)) {
                String newElseExpression = ControlFlowBasedVulnerabilityCorrection.getValueVariablesReplacement(elseExpression);
                conditional.setElseExpression(factory.createCodeSnippetExpression(newElseExpression));
            }
        }

        newAssigned = getNewAssigned(factory, assigned, type, dependableVariables);

        CtLocalVariableReference variableReference = factory.createLocalVariableReference(type, newAssigned);
        CtAssignment<?, ?> variableAssignment = factory.createVariableAssignment(variableReference, false, assignment);
        variableAssignment.setType(type);
        return new CtStatement[]{oldAssignment, variableAssignment};
    }

    /**
     * The method were the list of secret variables is updated given a new found secret.
     * @param factory			The factory used to create new instructions.
     * @param secretVariables	The list of secret variables to be updated.
     * @param assigned			The expression containing the new secret variable.
     * @param assignment		The value assigned to the secret variable.
     */
    private static void updateSecretVariables(Factory factory,
                                              List<CtVariable<?>> secretVariables,
                                              CtExpression<?> assigned,
                                              CtExpression assignment) {
        CtVariable<?> secretVariable;

        if (assigned instanceof CtArrayWrite) {
            CtArrayWrite<?> arrayWrite = (CtArrayWrite<?>) assigned;
            CtVariableReadImpl<?> localVariableReference = (CtVariableReadImpl<?>) arrayWrite.getTarget();
            CtVariableReference<?> variable = localVariableReference.getVariable();
            secretVariable = factory.createLocalVariable(variable.getType(), variable.getSimpleName(), null);
            logger.info("The assignment is an array write where the target is now a secret variable.");
        } else if (assigned instanceof CtVariableWrite) {
            CtVariableWrite<?> variable = (CtVariableWrite<?>) assigned;
            secretVariable = factory.createLocalVariable(variable.getType(), variable.toString(), assignment.clone());
        } else {
            secretVariable = (CtVariable<?>) assigned;
            logger.info("The assignment is to a variable, that is now a secret variable.");
        }

        if (secretVariable != null) {
            secretVariables.add(secretVariable);
            logger.info("Updating the list of secrets.");
        }
    }

    /**
     * The method responsible for obtaining the new assignment to be used.
     * @param factory               The factory used to create new instructions.
     * @param assigned              The existing assigned to be replaced.
     * @param type                  The type of the assigned.
     * @param dependableVariables   A set of the dependable variables. The variables used in the 'if' condition and all
     *                              variables where an assignment uses any of those variables. Meaning the variables that
     *                              can't be used outside the then statement.
     * @return                      The new representation of the assignment.
     */
    private static String getNewAssigned(Factory factory,
                                         CtExpression<?> assigned,
                                         CtTypeReference<?> type,
                                         Set<String> dependableVariables) {
        String newAssigned;
        if (assigned instanceof CtArrayWrite) {
            String arrayType = type.clone().getSimpleName();
            type = type.setSimpleName(type.getSimpleName() + "[]");
            CtArrayWrite<?> arrayWrite = (CtArrayWrite<?>) assigned;
            CtExpression<?> target = arrayWrite.getTarget();
            CtExpression<Integer> indexExpression = arrayWrite.getIndexExpression();

            if (ControlFlowBasedVulnerabilityCorrection.isKeyInVariablesReplacement(target.toString())) {
                logger.info("The assigned is a variable already replaced.");
                newAssigned = ControlFlowBasedVulnerabilityCorrection.getValueVariablesReplacement(target.toString());
            } else {
                newAssigned = NamingConvention.produceNewVariable();
                ControlFlowBasedVulnerabilityCorrection.addToVariablesReplacement(target.toString(), newAssigned);
                CtCodeSnippetExpression<Object> newArray = factory.createCodeSnippetExpression(String.format("new %s[%d]", arrayType, 1));
                ControlFlowBasedVulnerabilityCorrection.addToVariablesToAdd(newAssigned, type, newArray);
                logger.info("The assigned is a variable to be replaced.");
            }

            if (dependableVariables.contains(indexExpression.toString())) {
                newAssigned += "[0]";
            }
        } else {
            if (ControlFlowBasedVulnerabilityCorrection.isKeyInVariablesReplacement(assigned.toString())) {
                newAssigned = ControlFlowBasedVulnerabilityCorrection.getValueVariablesReplacement(assigned.toString());
                logger.info("The assigned is a variable already replaced.");
            } else {
                newAssigned = NamingConvention.produceNewVariable();
                ControlFlowBasedVulnerabilityCorrection.addToVariablesReplacement(assigned.toString(), newAssigned);
                ControlFlowBasedVulnerabilityCorrection.addToVariablesToAdd(newAssigned, type, null);
                logger.info("The assigned is a variable to be replaced.");
            }
        }
        return newAssigned;
    }
}