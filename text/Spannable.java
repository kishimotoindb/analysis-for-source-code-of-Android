package android.text;

/**语言标记可以通过这个接口作用到text
 * This is the interface for text to which markup objects can be
 * attached and detached.  Not all Spannable classes have mutable text;
 * see {@link Editable} for that.
 */
public interface Spannable
extends Spanned
{
    /**
     * Attach the specified markup object to the range <code>start&hellip;end</code>
     * of the text, or move the object to that range if it was already
     * attached elsewhere.  See {@link Spanned} for an explanation of
     * what the flags mean.  The object can be one that has meaning only
     * within your application, or it can be one that the text system will
     * use to affect text display or behavior.  Some noteworthy ones are
     * the subclasses of {@link android.text.style.CharacterStyle} and
     * {@link android.text.style.ParagraphStyle}, and
     * {@link android.text.TextWatcher} and
     * {@link android.text.SpanWatcher}.
     */
    public void setSpan(Object what, int start, int end, int flags);

    /**
     * Remove the specified object from the range of text to which it
     * was attached, if any.  It is OK to remove an object that was never
     * attached in the first place.
     */
    public void removeSpan(Object what);

    /**
     * Factory used by TextView to create new Spannables.  You can subclass
     * it to provide something other than SpannableString.
     */
    public static class Factory {
        private static Spannable.Factory sInstance = new Spannable.Factory();

        /**
         * Returns the standard Spannable Factory.
         */ 
        public static Spannable.Factory getInstance() {
            return sInstance;
        }

        /**
         * Returns a new SpannableString from the specified CharSequence.
         * You can override this to provide a different kind of Spannable.
         */
        public Spannable newSpannable(CharSequence source) {
            return new SpannableString(source);
        }
    }
}
