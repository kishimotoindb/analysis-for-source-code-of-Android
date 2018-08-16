package android.text.method;

/**
 * TransformationMethod2 extends the TransformationMethod interface
 * and adds the ability to relax restrictions of TransformationMethod.
 *
 * @hide
 */
public interface TransformationMethod2 extends TransformationMethod {
    /**
     * Relax the contract of TransformationMethod to allow length changes,
     * or revert to the length-restricted behavior.
     *
     * @param allowLengthChanges true to allow the transformation to change the length
     *                           of the input string.
     */
    public void setLengthChangesAllowed(boolean allowLengthChanges);
}
