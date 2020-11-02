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
 * When an object of a type is attached to an Editable, its methods will
 * be called when the text is changed.
 *
 * 这三个方法的区别
 * beforeTextChanged()
 * onTextChanged()
 * afterTextChanged()
 * 1.前两个方法主要作用是提供字符变化的信息，最后一个是提供校正操作的机会。所以前两个是不可操作只可读，最后一个
 * 是可操作但没什么可读性的方法。
 */
public interface TextWatcher extends NoCopySpan {
    /**
     * This method is called to notify you that, within <code>s</code>,
     * the <code>count</code> characters beginning at <code>start</code>
     * are about to be replaced by new text with length <code>after</code>.
     * It is an error to attempt to make changes to <code>s</code> from
     * this callback.
     *
     * s：输入下一个字符或者粘贴下一个字符(串)前，当前显示的文本内容
     * start：输入下一个字符或者粘贴下一个字符(串)前，光标所在的位置
     * count：将要被替换的文本长度
     * after：新输入文本的长度
     *
     * 从注释可以看出，before的时候，只是知道源字符串从第几个字符到第几个字符将被替换，并且新的字符有多长，
     * 但是完全没有提供新字符串的字符是什么。
     *
     * 1）插入新字符：start-插入位置；count=0，因为源字符串中没有字符会被替换；after，新字符串的长度
     * 2）替换：
     *  a.选中某些字符，然后从键盘输入新的字符
     *  b.选中某些字符，长按粘贴，原有字符（串）被替换为新的字符（串）
     *  start-选中字符的第一个字符的index
     *  count-选中字符的数量
     *  after-替换原有字符（串）的字符（串）的字符数量
     * 3) 删除：start-被删除字符（串）的第一个字符的位置；count-删除的字符数量；after=0，删除字符没有新字符，所以
     *  after为零
     *
     * 这里的s不要直接保存成成员变量进行使用，内容会变化。如果需要提前保存before的字符串，应该mBefore=s.toString()
     *
     */
    public void beforeTextChanged(CharSequence s/*输入前的字符串*/, int start,
                                  int count, int after);
    /**
     * This method is called to notify you that, within <code>s</code>,
     * the <code>count</code> characters beginning at <code>start</code>
     * have just replaced old text that had length <code>before</code>.
     * It is an error to attempt to make changes to <code>s</code> from
     * this callback.
     * s：输入下一个字符或者粘贴下一个字符(串)后，当前显示的文本内容。结果就是实时输入的结果
     * start：输入下一个字符或者粘贴下一个字符(串)前，光标所在的位置
     * before：被替换的文本的长度
     * count：新输入文本的长度
     *
     * 注意：
     * 输入前EditText上显示的是star，输入t，EditText的mText被设置为是start，即输入后的完整的新字符串。
     * s是输入后的完整的新字符串，这个回调描述的是新字符串的哪个子串是新输入的
     */
    public void onTextChanged(CharSequence s/*输入后的字符串*/, int start, int before, int count);

    /**
     * This method is called to notify you that, somewhere within
     * <code>s</code>, the text has been changed.
     * It is legitimate to make further changes to <code>s</code> from
     * this callback, but be careful not to get yourself into an infinite
     * loop, because any changes you make will cause this method to be
     * called again recursively.
     * (You are not told where the change took place because other
     * afterTextChanged() methods may already have made other changes
     * and invalidated the offsets.  But if you need to know here,
     * you can use {@link Spannable#setSpan} in {@link #onTextChanged}
     * to mark your place and then look up from here where the span
     * ended up.
     * s：输入下一个字符或者粘贴下一个字符(串)后，当前显示的文本内容。结果就是实时输入的结果。
     *   同onTextChanged的s。
     *
     * 这个回调可以直接修改s，并且修改结果会反应到TextView上。不需要调用textView.setText()，直接修改
     * Editable然后返回，修改后的结果就会展示到TextView上。
     *
     * 在afterTextChanged()中对Editable进行append、delete等操作，不会退出当前的afterTextChanged()，
     * 直接在它内部就会调用beforeTextChanged、onTextChanged和afterTextChanged，是嵌套的，也即是Editable
     * 内部直接触发了再一次的回调。
     */
    public void afterTextChanged(Editable s);
}
