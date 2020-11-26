/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.text;

/**
 * InputFilters can be attached to {@link Editable}s to constrain the
 * changes that can be made to them.
 */
public interface InputFilter
{
    /**
     * This method is called when the buffer is going to replace the
     * range <code>dstart &hellip; dend</code> of <code>dest</code>
     * with the new text from the range <code>start &hellip; end</code>
     * of <code>source</code>.  Return the CharSequence that you would
     * like to have placed there instead, including an empty string
     * if appropriate, or <code>null</code> to accept the original
     * replacement.  Be careful to not to reject 0-length replacements,
     * as this is what happens when you delete text.  Also beware that
     * you should not attempt to make any changes to <code>dest</code>
     * from this method; you may only examine it for context.
     * 
     * Note: If <var>source</var> is an instance of {@link Spanned} or
     * {@link Spannable}, the span objects in the <var>source</var> should be 
     * copied into the filtered result (i.e. the non-null return value). 
     * {@link TextUtils#copySpansFrom} can be used for convenience.
     */
    /*
     * 按照EditText使用场景描述：
     * source是用户输入
     * dest是"当前屏幕上正在展示的内容"
     *
     * 注：禁止修改dest
     */
    public CharSequence filter(CharSequence source, int start, int end,
                               Spanned dest, int dstart, int dend);

    /**
     * This filter will capitalize all the lower case letters that are added
     * through edits.
     */
    public static class AllCaps implements InputFilter {
        public CharSequence filter(CharSequence source, int start, int end,
                                   Spanned dest, int dstart, int dend) {
            for (int i = start; i < end; i++) {
                if (Character.isLowerCase(source.charAt(i))) {
                    char[] v = new char[end - start];
                    TextUtils.getChars(source, start, end, v, 0);
                    String s = new String(v).toUpperCase();

                    if (source instanceof Spanned) {
                        SpannableString sp = new SpannableString(s);
                        TextUtils.copySpansFrom((Spanned) source,
                                                start, end, null, sp, 0);
                        return sp;
                    } else {
                        return s;
                    }
                }
            }

            return null; // keep original
        }
    }

    /**
     * This filter will constrain edits not to make the length of the text
     * greater than the specified length.
     */
    public static class LengthFilter implements InputFilter {
        private final int mMax;

        public LengthFilter(int max) {
            mMax = max;
        }

        public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                int dstart, int dend) {
            /*
             * 以EditText输入为场景进行说明
             * dest是当前控件上展示的内容，dend-dstart表示dest中将要被替换的部分的字符长度
             * 所以keep就是当前可以插入的最大字符数量
             */
            int keep = mMax - (dest.length() - (dend - dstart));
            // 可插入的字符数为0，所以不允许新输入的内容上屏
            if (keep <= 0) {
                return "";
            } else if (keep >= end - start) {
                // 新输入的字符序列的字符数小于最大可插入字符数，原封不动的插入source
                return null; // keep original
            } else {
                // dest没有空间插入所有新输入的字符，从输入的字符序列中截取keep长度的字符串，然后插入到
                // dest中。
                keep += start;
                if (Character.isHighSurrogate(source.charAt(keep - 1))) {
                    --keep;
                    if (keep == start) {
                        return "";
                    }
                }
                return source.subSequence(start, keep);
            }
        }

        /**
         * @return the maximum length enforced by this input filter
         */
        public int getMax() {
            return mMax;
        }
    }
}
