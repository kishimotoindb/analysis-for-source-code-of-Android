/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.support.v7.widget;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class to manage children.
 * <p>
 * It wraps a RecyclerView and adds ability to hide some children. There are two sets of methods
 * provided by this class. <b>Regular</b> methods are the ones that replicate ViewGroup methods
 * like getChildAt, getChildCount etc. These methods ignore hidden children.
 * <p>
 * When RecyclerView needs direct access to the view group children, it can call unfiltered
 * methods like get getUnfilteredChildCount or getUnfilteredChildAt.
 */
class ChildHelper {

    private static final boolean DEBUG = false;

    private static final String TAG = "ChildrenHelper";

    // 这个Callback就是为了避免Helper中保存RecyclerView实例，Helper直接通过这个
    // callback回调RecyclerView的相应方法。
    // Callback对应的是ViewGroup中相应方法的默认实现。比如callback.getChildAt()，其实就
    // 是ViewGroup.getChildAt()
    final Callback mCallback;

    final Bucket mBucket;

    final List<View> mHiddenViews;

    ChildHelper(Callback callback) {
        mCallback = callback;
        mBucket = new Bucket();
        mHiddenViews = new ArrayList<View>();
    }

    /**
     * Marks a child view as hidden
     *
     * @param child  View to hide.
     */
    private void hideViewInternal(View child) {
        mHiddenViews.add(child);
        mCallback.onEnteredHiddenState(child);
    }

    /**
     * Unmarks a child view as hidden.
     *
     * @param child  View to hide.
     */
    private boolean unhideViewInternal(View child) {
        if (mHiddenViews.remove(child)) {
            mCallback.onLeftHiddenState(child);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Adds a view to the ViewGroup
     *
     * @param child  View to add.
     * @param hidden If set to true, this item will be invisible from regular methods.
     */
    void addView(View child, boolean hidden) {
        addView(child, -1, hidden);
    }

    /**
     * Add a view to the ViewGroup at an index
     *
     * @param child  View to add.
     * @param index  Index of the child from the regular perspective (excluding hidden views).
     *               ChildHelper offsets this index to actual ViewGroup index.
     * @param hidden If set to true, this item will be invisible from regular methods.
     */
    /*
     * 这里的index是相对的，即这个view在所有可见的child中应该排在什么位置。因为child中还有
     * 不可见的view，即mChildren[]中既有可见的child，也有不可见的child，所以这个view在放入
     * mChildren[]中时，recyclerview需要对index做转换，保证这个view被放在mChildren[]中的
     * 正确位置上。
     * 注意，新add的这个view可以是可见，也可以是不可见。
     */
    void addView(View child, int index, boolean hidden) {
        final int offset;
        if (index < 0) {
            offset = mCallback.getChildCount();
        } else {
            offset = getOffset(index);
        }
        mBucket.insert(offset, hidden);
        if (hidden) {
            hideViewInternal(child);
        }
        mCallback.addView(child, offset);
        if (DEBUG) {
            Log.d(TAG, "addViewAt " + index + ",h:" + hidden + ", " + this);
        }
    }

    /*
     * 这个方法负责把child在visibleChildren中的索引位置转换为在RecyclerView的mChildren中的实际索引位置。
     *
     * index表示的是当前view在所有可见的child中的排序位置，但是因为RecyclerView的mChildren[]
     * 中既保存了可见的child，又保存了不可见的child，所以这个index未必是当前view在mChildren[]
     * 中的实际index。getOffset()方法就是用来对index进行转换，得到当前view在mChildren[]中的
     * 实际位置。
     * 另外有个细节，下面这段代码中有这么一段，就是保证新加入的view尽可能的放在hidden view
     * 的后面。
     * while (mBucket.get(offset)) { // ensure this offset is not hidden
     *      offset ++;
     * }
     */

    /*
     * 这个是我自己实现的转换代码。缺点，总是需要从头遍历。
     *
     *   private int getOffsetSelfImpl(int index) {
            final int limit = mCallback.getChildCount();
            final int requiredVisiblePos = index;
            int currentVisiblePos = -1;
            for (int i = 0; i < limit ; i++) {
                if (!mBucket.get(i)) {
                    currentVisiblePos++;
                }
                if (currentVisiblePos == requiredVisiblePos) {
                    return i;
                }
            }
            return -1;
    *    }
    *
    * 下面是android自己的实现，他的实现是基于这样一个事实：view在visible children中的index，一定小于
    * 等于其在mChildren中的index。因为mChildren中可能插入了hidden view。
    * 所以下面的实现考虑了这一点，对转换算法做了优化，尽量减少遍历的次数。
    *
    * 这里进行优化需要用到很多中间过程的信息，所以之所以最终能用下面的算法，也是因为在每个中间过程，都将
    * 相应的信息保存到了mData，从而在最终使用的时候，利用中间信息降低了算法的时间复杂度。
    *
    * 所有从小范围到大范围的转换，按道理都可以利用中间信息避免从头到尾的遍历。并且每次循环都可以为后面的循环
    * 提供更多的中间信息。
    */
    private int getOffset(int index) {
        if (index < 0) {
            return -1; //anything below 0 won't work as diff will be undefined.
        }
        // RecyclerView的mChildren中保存的所有view
        final int limit = mCallback.getChildCount();
        int offset = index;
        // index转换是一定需要遍历的，但是比起上面我自己实现的代码，这里的遍历是跳步进行的，所以会显著减少
        // 遍历的总耗时。
        /*
         * mChildren[]索引:           0 1 2 3 4 5 6 7
         * mChildren[]数组:           1 0 0 1 0 0 1 1 （1表示visible，0表示hidden）
         * visible child index：      0     1     2 3
         * 对visible index=3的child进行转换：
         * 下面是每轮循环的参数变化情况：
         * 1) offset=index=3 -> removedBefore=2 -> diff=3-(3-2)=2
         * 2) offset=offset+diff=3+2=5, index=3 -> removedBefore=3 -> diff=3-(5-3)=1
         * 3) offset=offset+diff=5+1=6, index=3 -> removedBefore=4 -> diff=3-(6-4)=1
         * 4) offset=offset+diff=6+1, index=3 -> removedBefore=4 -> diff=3-(7-4)=0
         * 总共循环了4轮
         *
         * 如果要是使用上面自己的实现，就要循环8轮。所以android的实现还是更优的选择。RecyclerView为什么
         * 要对这个遍历做简单的算法设计？
         * 相对于我们的业务流程，对view的遍历可能最多在一个业务流程中进行一两次。但是对于RecyclerView来说，
         * 各个地方都需要调用getOffset(int)方法对view的index进行转换，那么在一个layout过程中间就可能进行
         * 至少数十次或者几十次这种遍历，所以肯定不能接受从头遍历到尾的方式。这么来看，多刷刷leetcode还是
         * 挺有用的，实际开发中还是有用武之地的。
         */
        while (offset < limit) {
            // before是指当前offset->0，不包括offset本身
            final int removedBefore = mBucket.countOnesBefore(offset);
            // 通过这一步，实现了跳步遍历。offset - removedBefore的结果实际上是一个慢慢逼近index的过程，
            // 直到两者相等为止。
            final int diff = index - (offset - removedBefore);
            if (diff == 0) {
                /* diff=0，说明mChildren中，index之前都没有hidden view，所以当前view在visible children
                 * 和 mChildren中的index可能是一致的。
                 *
                 * 比如转换b的index
                 * visible children[]: a b c d
                 * mChildren[]: a b c d
                 * 这种情况下b的index就是一致的
                 *
                 * 但是也有特殊情况，比如转换b的index时，在b的index（1）的位置上，正好是一个hidden view
                 * visible children[]: a b c d
                 * mChildren[]: a e b c d
                 * 此时diff也等于0，但是因为正好再index=1的位置是hidden view，所以需要找到最终的位置。
                 */
                while (mBucket.get(offset)) { // ensure this offset is not hidden
                    offset ++;
                }
                return offset;
            } else {

                offset += diff;
            }
        }
        return -1;
    }

    /**
     * Removes the provided View from underlying RecyclerView.
     *
     * @param view The view to remove.
     */
    void removeView(View view) {
        int index = mCallback.indexOfChild(view);
        if (index < 0) {
            return;
        }
        if (mBucket.remove(index)) {
            unhideViewInternal(view);
        }
        mCallback.removeViewAt(index);
        if (DEBUG) {
            Log.d(TAG, "remove View off:" + index + "," + this);
        }
    }

    /**
     * Removes the view at the provided index from RecyclerView.
     *
     * @param index Index of the child from the regular perspective (excluding hidden views).
     *              ChildHelper offsets this index to actual ViewGroup index.
     */
    /*
     * 把需要remove的view从所有地方移除：mBucket、mChildren[]
     */
    void removeViewAt(int index) {
        final int offset = getOffset(index);
        final View view = mCallback.getChildAt(offset);
        if (view == null) {
            return;
        }
        if (mBucket.remove(offset)) {
            unhideViewInternal(view);
        }
        mCallback.removeViewAt(offset);
        if (DEBUG) {
            Log.d(TAG, "removeViewAt " + index + ", off:" + offset + ", " + this);
        }
    }

    /**
     * Returns the child at provided index.
     *
     * @param index Index of the child to return in regular perspective.
     */
    View getChildAt(int index) {
        final int offset = getOffset(index);
        return mCallback.getChildAt(offset);
    }

    /**
     * Removes all views from the ViewGroup including the hidden ones.
     */
    void removeAllViewsUnfiltered() {
        mBucket.reset();
        for (int i = mHiddenViews.size() - 1; i >= 0; i--) {
            mCallback.onLeftHiddenState(mHiddenViews.get(i));
            mHiddenViews.remove(i);
        }
        mCallback.removeAllViews();
        if (DEBUG) {
            Log.d(TAG, "removeAllViewsUnfiltered");
        }
    }

    /**
     * This can be used to find a disappearing view by position.
     *
     * @param position The adapter position of the item.
     * @param type     View type, can be {@link RecyclerView#INVALID_TYPE}.
     * @return         A hidden view with a valid ViewHolder that matches the position and type.
     */
    View findHiddenNonRemovedView(int position, int type) {
        final int count = mHiddenViews.size();
        for (int i = 0; i < count; i++) {
            final View view = mHiddenViews.get(i);
            RecyclerView.ViewHolder holder = mCallback.getChildViewHolder(view);
            if (holder.getLayoutPosition() == position && !holder.isInvalid() &&
                    (type == RecyclerView.INVALID_TYPE || holder.getItemViewType() == type)) {
                return view;
            }
        }
        return null;
    }

    /**
     * Attaches the provided view to the underlying ViewGroup.
     *
     * @param child        Child to attach.
     * @param index        Index of the child to attach in regular perspective.
     * @param layoutParams LayoutParams for the child.
     * @param hidden       If set to true, this item will be invisible to the regular methods.
     */
    void attachViewToParent(View child, int index, ViewGroup.LayoutParams layoutParams,
            boolean hidden) {
        final int offset;
        if (index < 0) {
            offset = mCallback.getChildCount();
        } else {
            offset = getOffset(index);
        }
        mBucket.insert(offset, hidden);
        if (hidden) {
            hideViewInternal(child);
        }
        mCallback.attachViewToParent(child, offset, layoutParams);
        if (DEBUG) {
            Log.d(TAG, "attach view to parent index:" + index + ",off:" + offset + "," +
                    "h:" + hidden + ", " + this);
        }
    }

    /**
     * Returns the number of children that are not hidden.
     *
     * @return Number of children that are not hidden.
     * @see #getChildAt(int)
     */
    int getChildCount() {
        return mCallback.getChildCount() - mHiddenViews.size();
    }

    /**
     * Returns the total number of children.
     *
     * @return The total number of children including the hidden views.
     * @see #getUnfilteredChildAt(int)
     */
    int getUnfilteredChildCount() {
        return mCallback.getChildCount();
    }

    /**
     * Returns a child by ViewGroup offset. ChildHelper won't offset this index.
     *
     * @param index ViewGroup index of the child to return.
     * @return The view in the provided index.
     */
    View getUnfilteredChildAt(int index) {
        return mCallback.getChildAt(index);
    }

    /**
     * Detaches the view at the provided index.
     *
     * @param index Index of the child to return in regular perspective.
     */
    void detachViewFromParent(int index) {
        final int offset = getOffset(index);
        mBucket.remove(offset);
        mCallback.detachViewFromParent(offset);
        if (DEBUG) {
            Log.d(TAG, "detach view from parent " + index + ", off:" + offset);
        }
    }

    /**
     * Returns the index of the child in regular perspective.
     *
     * @param child The child whose index will be returned.
     * @return The regular perspective index of the child or -1 if it does not exists.
     */
    int indexOfChild(View child) {
        final int index = mCallback.indexOfChild(child);
        if (index == -1) {
            return -1;
        }
        if (mBucket.get(index)) {
            if (DEBUG) {
                throw new IllegalArgumentException("cannot get index of a hidden child");
            } else {
                return -1;
            }
        }
        // reverse the index
        return index - mBucket.countOnesBefore(index);
    }

    /**
     * Returns whether a View is visible to LayoutManager or not.
     *
     * @param view The child view to check. Should be a child of the Callback.
     * @return True if the View is not visible to LayoutManager
     */
    boolean isHidden(View view) {
        return mHiddenViews.contains(view);
    }

    /**
     * Marks a child view as hidden.
     *
     * @param view The view to hide.
     */
    void hide(View view) {
        final int offset = mCallback.indexOfChild(view);
        if (offset < 0) {
            throw new IllegalArgumentException("view is not a child, cannot hide " + view);
        }
        if (DEBUG && mBucket.get(offset)) {
            throw new RuntimeException("trying to hide same view twice, how come ? " + view);
        }
        mBucket.set(offset);
        hideViewInternal(view);
        if (DEBUG) {
            Log.d(TAG, "hiding child " + view + " at offset " + offset+ ", " + this);
        }
    }

    @Override
    public String toString() {
        return mBucket.toString() + ", hidden list:" + mHiddenViews.size();
    }

    /**
     * Removes a view from the ViewGroup if it is hidden.
     *
     * @param view The view to remove.
     * @return True if the View is found and it is hidden. False otherwise.
     */
    boolean removeViewIfHidden(View view) {
        final int index = mCallback.indexOfChild(view);
        if (index == -1) {
            if (unhideViewInternal(view) && DEBUG) {
                throw new IllegalStateException("view is in hidden list but not in view group");
            }
            return true;
        }
        if (mBucket.get(index)) {
            mBucket.remove(index);
            if (!unhideViewInternal(view) && DEBUG) {
                throw new IllegalStateException(
                        "removed a hidden view but it is not in hidden views list");
            }
            mCallback.removeViewAt(index);
            return true;
        }
        return false;
    }

    /**
     * Bitset implementation that provides methods to offset indices.
     *
     * RecyclerView的mChildren[]数组中既有可见的view，也有不可见的view，Bucket通过
     * 最多128个二级制位，记录mChildren[]中所有不可见view的位置（即不可见view在mChildren[]
     * 中的index）
     *
     * 1）child view在mData的第几位，与RecyclerView的mChildren数组中的index相同。
     * 2）Bucket后面最多再跟一个Bucket，所以也就是默认RecyclerView最多有128个child。
     */
    static class Bucket {

        final static int BITS_PER_WORD = Long.SIZE;

        final static long LAST_BIT = 1L << (Long.SIZE - 1);

        long mData = 0;

        Bucket next;

        // 就是用128位表示当前RecyclerView的mChildren数组中的各个索引位置
        void set(int index) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                next.set(index - BITS_PER_WORD);
            } else {
                mData |= 1L << index;
            }
        }

        private void ensureNext() {
            if (next == null) {
                next = new Bucket();
            }
        }

        void clear(int index) {
            if (index >= BITS_PER_WORD) {
                if (next != null) {
                    next.clear(index - BITS_PER_WORD);
                }
            } else {
                mData &= ~(1L << index);
            }

        }

        boolean get(int index) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                return next.get(index - BITS_PER_WORD);
            } else {
                return (mData & (1L << index)) != 0;
            }
        }

        void reset() {
            mData = 0;
            if (next != null) {
                next.reset();
            }
        }

        /*
         * 这个方法是在addView的时候使用的，所有会有前后移动原有数据的操作。即，addView的时候，隐藏的view的
         * 位置会发生移动，所有需要同时更新bucket中保存的位置信息。
         *
         * 相当于在一个列表的某个位置插入一个新的元素，位于新元素前面的元素，位置不需要改变，
         * 位于新元素当前插入位置及其后面的元素，都需要向前移动一位。
         */
        void insert(int index, boolean value) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                next.insert(index - BITS_PER_WORD, value);
            } else {
                final boolean lastBit = (mData & LAST_BIT) != 0;
                long mask = (1L << index) - 1;
                final long before = mData & mask;
                final long after = ((mData & ~mask)) << 1;
                mData = before | after;
                if (value) {
                    set(index);
                } else {
                    clear(index);
                }
                if (lastBit || next != null) {
                    ensureNext();
                    next.insert(0, lastBit);
                }
            }
        }

        boolean remove(int index) {
            if (index >= BITS_PER_WORD) {
                ensureNext();
                return next.remove(index - BITS_PER_WORD);
            } else {
                long mask = (1L << index);
                final boolean value = (mData & mask) != 0;
                mData &= ~mask;
                mask = mask - 1;
                final long before = mData & mask;
                // cannot use >> because it adds one.
                final long after = Long.rotateRight(mData & ~mask, 1);
                mData = before | after;
                if (next != null) {
                    if (next.get(0)) {
                        set(BITS_PER_WORD - 1);
                    }
                    next.remove(0);
                }
                return value;
            }
        }

        /*
         * 计算mData中，位于index之前的位置上，有多少hidden的view。index是相对于mData来说的，不关心
         * 外部传入时是怎么定义的。比如外部传入的index，其含义是当前view在所有可见child中的位置，但是
         * 在调用到这个方法里面后，参数index的含义就是对应mData的索引位置。
         *
         * before不包括index本身。即 index=2，只考虑0和1是不是hidden view
         */
        int countOnesBefore(int index) {
            if (next == null) {
                if (index >= BITS_PER_WORD) {
                    return Long.bitCount(mData);
                }
                return Long.bitCount(mData & ((1L << index) - 1));
            }
            if (index < BITS_PER_WORD) {
                return Long.bitCount(mData & ((1L << index) - 1));
            } else {
                return next.countOnesBefore(index - BITS_PER_WORD) + Long.bitCount(mData);
            }
        }

        @Override
        public String toString() {
            return next == null ? Long.toBinaryString(mData)
                    : next.toString() + "xx" + Long.toBinaryString(mData);
        }
    }

    static interface Callback {

        int getChildCount();

        void addView(View child, int index);

        int indexOfChild(View view);

        void removeViewAt(int index);

        View getChildAt(int offset);

        void removeAllViews();

        RecyclerView.ViewHolder getChildViewHolder(View view);

        void attachViewToParent(View child, int index, ViewGroup.LayoutParams layoutParams);

        void detachViewFromParent(int offset);

        void onEnteredHiddenState(View child);

        void onLeftHiddenState(View child);
    }
}
