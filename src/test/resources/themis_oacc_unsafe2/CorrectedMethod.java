public static boolean ArraysIsEquals$Modification(char[] a, char[] a2) {
    boolean $1 = true;
    if (a == a2) {
        $1 = true;
    }
    if ((a == null) || (a2 == null)) {
        $1 = false;
    }
    int length = 0;
    if (a != null)
        length = a.length;

    if ((a2 != null) && (a2.length != length)) {
        $1 = false;
    }
    for (int i = 0; i < length; i++) {
        if ((((a != null) && (i < a.length)) && ((a2 != null) && (i < a2.length))) && (a[i] != a2[i])) {
            $1 = false;
        }
    }
    return $1;
}