package android.text.method;

import android.graphics.Rect;
import android.view.View;

/**
 * TextView uses TransformationMethods to do things like replacing the
 * characters of passwords with dots, or keeping the newline characters（指令输出装置进到下一行的控制符号）
 * from causing line breaks in single-line text fields（文本栏）.
 */
public interface TransformationMethod
{
    /**
     * Returns a CharSequence that is a transformation of the source text --
     * for example, replacing each character with a dot in a password field.
     * Beware that the returned text must be exactly the same length as
     * the source text, and that if the source text is Editable, the returned
     * text must mirror it dynamically instead of doing a one-time copy.
     */
    public CharSequence getTransformation(CharSequence source, View view);

    /**
     * This method is called when the TextView that uses this
     * TransformationMethod gains or loses focus.
     */
    public void onFocusChanged(View view, CharSequence sourceText,
                               boolean focused, int direction,
                               Rect previouslyFocusedRect);
}
