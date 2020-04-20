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

import android.support.v4.util.Pools;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.support.v7.widget.RecyclerView.*;

/**
 * Helper class that can enqueue and process adapter update operations.
 * <p>
 * To support animations, RecyclerView presents an older version the Adapter to best represent
 * previous state of the layout. Sometimes, this is not trivial when items(定语后置:that were not laid out) are removed that were
 * not laid out, in which case, RecyclerView has no way of providing that item's view for
 * animations.
 * <p>
 * AdapterHelper creates an UpdateOp for each adapter data change then pre-processes them. During
 * pre processing, AdapterHelper finds out which UpdateOps can be deferred to second layout pass
 * and which cannot. For the UpdateOps that cannot be deferred, AdapterHelper will change them
 * according to previously deferred operation and dispatch them before the first layout pass. It
 * also takes care of updating deferred UpdateOps since order of operations is changed by this
 * process.
 * <p>
 * Although operations may be forwarded to LayoutManager in different orders, resulting data set
 * is guaranteed to be the consistent.
 */
class AdapterHelper implements OpReorderer.Callback {

    final static int POSITION_TYPE_INVISIBLE = 0;

    final static int POSITION_TYPE_NEW_OR_LAID_OUT = 1;

    private static final boolean DEBUG = false;

    private static final String TAG = "AHT";

    private Pools.Pool<UpdateOp> mUpdateOpPool = new Pools.SimplePool<UpdateOp>(UpdateOp.POOL_SIZE);

    final ArrayList<UpdateOp> mPendingUpdates = new ArrayList<UpdateOp>();

    final ArrayList<UpdateOp> mPostponedList = new ArrayList<UpdateOp>();

    final Callback mCallback;

    Runnable mOnItemProcessedCallback;

    final boolean mDisableRecycler;

    final OpReorderer mOpReorderer;

    AdapterHelper(Callback callback) {
        this(callback, false);
    }

    AdapterHelper(Callback callback, boolean disableRecycler) {
        mCallback = callback;
        mDisableRecycler = disableRecycler;
        mOpReorderer = new OpReorderer(this);
    }

    AdapterHelper addUpdateOp(UpdateOp... ops) {
        Collections.addAll(mPendingUpdates, ops);
        return this;
    }

    void reset() {
        recycleUpdateOpsAndClearList(mPendingUpdates);
        recycleUpdateOpsAndClearList(mPostponedList);
    }

    void preProcess() {
        // 这里只是对所有的pendingUpdates进行重新排序，并不会对ViewHolder进行操作。实际修改holder状态的
        // 是下面的applyAdd()这些方法
        mOpReorderer.reorderOps(mPendingUpdates);
        final int count = mPendingUpdates.size();
        // 对排好序的pendingUpdates依次进行处理，同时将op更新到postponeList
        for (int i = 0; i < count; i++) {
            UpdateOp op = mPendingUpdates.get(i);
            switch (op.cmd) {
                case UpdateOp.ADD:
                    applyAdd(op);
                    break;
                case UpdateOp.REMOVE:
                    applyRemove(op);
                    break;
                case UpdateOp.UPDATE:
                    applyUpdate(op);
                    break;
                case UpdateOp.MOVE:
                    applyMove(op);
                    break;
            }
            if (mOnItemProcessedCallback != null) {
                mOnItemProcessedCallback.run();
            }
        }
        // mPendingUpdates保存了RecyclerView两次layout之间，所有待处理的item更新。在每次RecyclerView调用
        // onLayout真正执行布局之前，都会先把mPendingUpdates中的op处理完，并保存到mPostponedList中，最后
        // 将自己清空。
        mPendingUpdates.clear();
    }

    void consumePostponedUpdates() {
        final int count = mPostponedList.size();
        for (int i = 0; i < count; i++) {
            // 这里只是生命周期回调
            mCallback.onDispatchSecondPass(mPostponedList.get(i));
        }
        recycleUpdateOpsAndClearList(mPostponedList);
    }

    private void applyMove(UpdateOp op) {
        // MOVE ops are pre-processed so at this point, we know that item is still in the adapter.
        // otherwise, it would be converted into a REMOVE operation
        postponeAndUpdateViewHolders(op);
    }

    private void applyRemove(UpdateOp op) {
        int tmpStart = op.positionStart;
        int tmpCount = 0;
        int tmpEnd = op.positionStart + op.itemCount;
        int type = -1;
        for (int position = op.positionStart; position < tmpEnd; position++) {
            boolean typeChanged = false;
            ViewHolder vh = mCallback.findViewHolder(position);
            /*
             * 1.remove操作对应的ViewHolder还能出现不存在并且不是新添加的情况？什么情景会是这种情况？
             */
            if (vh != null || canFindInPreLayout(position)) {
                // If a ViewHolder exists or this is a newly added item, we can defer this update
                // to post layout stage.
                // * For existing ViewHolders, we'll fake its existence in the pre-layout phase.
                // * For items that are added and removed in the same process cycle, they won't
                // have any effect in pre-layout since their add ops are already deferred to
                // post-layout pass.
                if (type == POSITION_TYPE_INVISIBLE) {
                    // Looks like we have other updates that we cannot merge with this one.
                    // Create an UpdateOp and dispatch it to LayoutManager.
                    UpdateOp newOp = obtainUpdateOp(UpdateOp.REMOVE, tmpStart, tmpCount, null);
                    dispatchAndUpdateViewHolders(newOp);
                    typeChanged = true;
                }
                type = POSITION_TYPE_NEW_OR_LAID_OUT;
            } else {
                // This update cannot be recovered because we don't have a ViewHolder representing
                // this position. Instead, post it to LayoutManager immediately
                if (type == POSITION_TYPE_NEW_OR_LAID_OUT) {
                    // Looks like we have other updates that we cannot merge with this one.
                    // Create UpdateOp op and dispatch it to LayoutManager.
                    UpdateOp newOp = obtainUpdateOp(UpdateOp.REMOVE, tmpStart, tmpCount, null);
                    postponeAndUpdateViewHolders(newOp);
                    typeChanged = true;
                }
                type = POSITION_TYPE_INVISIBLE;
            }
            if (typeChanged) {
                position -= tmpCount; // also equal to tmpStart
                tmpEnd -= tmpCount;
                tmpCount = 1;
            } else {
                tmpCount++;
            }
        }
        if (tmpCount != op.itemCount) { // all 1 effect
            recycleUpdateOp(op);
            op = obtainUpdateOp(UpdateOp.REMOVE, tmpStart, tmpCount, null);
        }
        if (type == POSITION_TYPE_INVISIBLE) {
            dispatchAndUpdateViewHolders(op);
        } else {
            postponeAndUpdateViewHolders(op);
        }
    }

    private void applyUpdate(UpdateOp op) {
        int tmpStart = op.positionStart;
        int tmpCount = 0;
        int tmpEnd = op.positionStart + op.itemCount;
        int type = -1;
        for (int position = op.positionStart; position < tmpEnd; position++) {
            ViewHolder vh = mCallback.findViewHolder(position);
            if (vh != null || canFindInPreLayout(position)) { // deferred
                if (type == POSITION_TYPE_INVISIBLE) {
                    UpdateOp newOp = obtainUpdateOp(UpdateOp.UPDATE, tmpStart, tmpCount,
                            op.payload);
                    dispatchAndUpdateViewHolders(newOp);
                    tmpCount = 0;
                    tmpStart = position;
                }
                type = POSITION_TYPE_NEW_OR_LAID_OUT;
            } else { // applied
                if (type == POSITION_TYPE_NEW_OR_LAID_OUT) {
                    UpdateOp newOp = obtainUpdateOp(UpdateOp.UPDATE, tmpStart, tmpCount,
                            op.payload);
                    postponeAndUpdateViewHolders(newOp);
                    tmpCount = 0;
                    tmpStart = position;
                }
                type = POSITION_TYPE_INVISIBLE;
            }
            tmpCount++;
        }
        if (tmpCount != op.itemCount) { // all 1 effect
            Object payload = op.payload;
            recycleUpdateOp(op);
            op = obtainUpdateOp(UpdateOp.UPDATE, tmpStart, tmpCount, payload);
        }
        if (type == POSITION_TYPE_INVISIBLE) {
            dispatchAndUpdateViewHolders(op);
        } else {
            postponeAndUpdateViewHolders(op);
        }
    }

    private void dispatchAndUpdateViewHolders(UpdateOp op) {
        // tricky part.
        // traverse all postpones and revert their changes on this op if necessary, apply updated
        // dispatch to them since now they are after this op.
        if (op.cmd == UpdateOp.ADD || op.cmd == UpdateOp.MOVE) {
            throw new IllegalArgumentException("should not dispatch add or move for pre layout");
        }
        if (DEBUG) {
            Log.d(TAG, "dispatch (pre)" + op);
            Log.d(TAG, "postponed state before:");
            for (UpdateOp updateOp : mPostponedList) {
                Log.d(TAG, updateOp.toString());
            }
            Log.d(TAG, "----");
        }

        // handle each pos 1 by 1 to ensure continuity. If it breaks, dispatch partial
        // TODO Since move ops are pushed to end, we should not need this anymore
        int tmpStart = updatePositionWithPostponed(op.positionStart, op.cmd);
        if (DEBUG) {
            Log.d(TAG, "pos:" + op.positionStart + ",updatedPos:" + tmpStart);
        }
        int tmpCnt = 1;
        int offsetPositionForPartial = op.positionStart;
        final int positionMultiplier;
        switch (op.cmd) {
            case UpdateOp.UPDATE:
                positionMultiplier = 1;
                break;
            case UpdateOp.REMOVE:
                positionMultiplier = 0;
                break;
            default:
                throw new IllegalArgumentException("op should be remove or update." + op);
        }
        for (int p = 1; p < op.itemCount; p++) {
            final int pos = op.positionStart + (positionMultiplier * p);
            int updatedPos = updatePositionWithPostponed(pos, op.cmd);
            if (DEBUG) {
                Log.d(TAG, "pos:" + pos + ",updatedPos:" + updatedPos);
            }
            boolean continuous = false;
            switch (op.cmd) {
                case UpdateOp.UPDATE:
                    continuous = updatedPos == tmpStart + 1;
                    break;
                case UpdateOp.REMOVE:
                    continuous = updatedPos == tmpStart;
                    break;
            }
            if (continuous) {
                tmpCnt++;
            } else {
                // need to dispatch this separately
                UpdateOp tmp = obtainUpdateOp(op.cmd, tmpStart, tmpCnt, op.payload);
                if (DEBUG) {
                    Log.d(TAG, "need to dispatch separately " + tmp);
                }
                dispatchFirstPassAndUpdateViewHolders(tmp, offsetPositionForPartial);
                recycleUpdateOp(tmp);
                if (op.cmd == UpdateOp.UPDATE) {
                    offsetPositionForPartial += tmpCnt;
                }
                tmpStart = updatedPos;// need to remove previously dispatched
                tmpCnt = 1;
            }
        }
        Object payload = op.payload;
        recycleUpdateOp(op);
        if (tmpCnt > 0) {
            UpdateOp tmp = obtainUpdateOp(op.cmd, tmpStart, tmpCnt, payload);
            if (DEBUG) {
                Log.d(TAG, "dispatching:" + tmp);
            }
            dispatchFirstPassAndUpdateViewHolders(tmp, offsetPositionForPartial);
            recycleUpdateOp(tmp);
        }
        if (DEBUG) {
            Log.d(TAG, "post dispatch");
            Log.d(TAG, "postponed state after:");
            for (UpdateOp updateOp : mPostponedList) {
                Log.d(TAG, updateOp.toString());
            }
            Log.d(TAG, "----");
        }
    }

    void dispatchFirstPassAndUpdateViewHolders(UpdateOp op, int offsetStart) {
        mCallback.onDispatchFirstPass(op);
        switch (op.cmd) {
            case UpdateOp.REMOVE:
                mCallback.offsetPositionsForRemovingInvisible(offsetStart, op.itemCount);
                break;
            case UpdateOp.UPDATE:
                mCallback.markViewHoldersUpdated(offsetStart, op.itemCount, op.payload);
                break;
            default:
                throw new IllegalArgumentException("only remove and update ops can be dispatched"
                        + " in first pass");
        }
    }

    private int updatePositionWithPostponed(int pos, int cmd) {
        final int count = mPostponedList.size();
        for (int i = count - 1; i >= 0; i--) {
            UpdateOp postponed = mPostponedList.get(i);
            if (postponed.cmd == UpdateOp.MOVE) {
                int start, end;
                if (postponed.positionStart < postponed.itemCount) {
                    start = postponed.positionStart;
                    end = postponed.itemCount;
                } else {
                    start = postponed.itemCount;
                    end = postponed.positionStart;
                }
                if (pos >= start && pos <= end) {
                    //i'm affected
                    if (start == postponed.positionStart) {
                        if (cmd == UpdateOp.ADD) {
                            postponed.itemCount++;
                        } else if (cmd == UpdateOp.REMOVE) {
                            postponed.itemCount--;
                        }
                        // op moved to left, move it right to revert
                        pos++;
                    } else {
                        if (cmd == UpdateOp.ADD) {
                            postponed.positionStart++;
                        } else if (cmd == UpdateOp.REMOVE) {
                            postponed.positionStart--;
                        }
                        // op was moved right, move left to revert
                        pos--;
                    }
                } else if (pos < postponed.positionStart) {
                    // postponed MV is outside the dispatched OP. if it is before, offset
                    if (cmd == UpdateOp.ADD) {
                        postponed.positionStart++;
                        postponed.itemCount++;
                    } else if (cmd == UpdateOp.REMOVE) {
                        postponed.positionStart--;
                        postponed.itemCount--;
                    }
                }
            } else {
                if (postponed.positionStart <= pos) {
                    if (postponed.cmd == UpdateOp.ADD) {
                        pos -= postponed.itemCount;
                    } else if (postponed.cmd == UpdateOp.REMOVE) {
                        pos += postponed.itemCount;
                    }
                } else {
                    if (cmd == UpdateOp.ADD) {
                        postponed.positionStart++;
                    } else if (cmd == UpdateOp.REMOVE) {
                        postponed.positionStart--;
                    }
                }
            }
            if (DEBUG) {
                Log.d(TAG, "dispath (step" + i + ")");
                Log.d(TAG, "postponed state:" + i + ", pos:" + pos);
                for (UpdateOp updateOp : mPostponedList) {
                    Log.d(TAG, updateOp.toString());
                }
                Log.d(TAG, "----");
            }
        }
        for (int i = mPostponedList.size() - 1; i >= 0; i--) {
            UpdateOp op = mPostponedList.get(i);
            if (op.cmd == UpdateOp.MOVE) {
                if (op.itemCount == op.positionStart || op.itemCount < 0) {
                    mPostponedList.remove(i);
                    recycleUpdateOp(op);
                }
            } else if (op.itemCount <= 0) {
                mPostponedList.remove(i);
                recycleUpdateOp(op);
            }
        }
        return pos;
    }

    /*
     * 疑问：
     * 2.每次执行layout，只有被放到mPostponeList中的op会执行preLayout? 应该是，但是不是list中的所有op都
     *   进行preLayout，因为remove也会被放到mPostponeList中，但是canFindInPreLayout()方法中没有对remove
     *   进行处理
     * 3.applyRemove传过来的position只是当前remove操作时的position，这里被用来当做最终的位置，为什么？
     *
     * 结论：
     * 参数position：mPostponeList中所有操作都执行结束之后的位置
     * 判断逻辑：mPostponeList列表中的add或者move操作所涉及的初始位置，在经过一系列移动之后，最终与传入的
     *          position是否相同。如果相同，说明这个position上的item在前一个动画执行周期里，进行过add或者
     *          move动画。而add和move操作是会进行preLayout流程的，所以认为传入的position是可以在preLayout
     *          中找到的。
     *
     * 1.只有add和move两种类型的Op有prelayout过程。
     * 2.这个方法基本等同于判断动画执行结束后的某个position对应的item是否在上一个动画周期中是否执行了add或者
     *   move操作（注意是操作，不是动画，因为add和move只是UpdateOp，不一定对应到appearance等动画类型）
     *
     */
    private boolean canFindInPreLayout(int position) {
        final int count = mPostponedList.size();
        for (int i = 0; i < count; i++) {
            UpdateOp op = mPostponedList.get(i);
            if (op.cmd == UpdateOp.MOVE) {
                if (findPositionOffset(op.itemCount, i + 1) == position) {
                    return true;
                }
            } else if (op.cmd == UpdateOp.ADD) {
                // TODO optimize.
                final int end = op.positionStart + op.itemCount;
                for (int pos = op.positionStart; pos < end; pos++) {
                    if (findPositionOffset(pos, i + 1) == position) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // add的操作就是将op放入到mPostponedList中，然后更新viewHolder中的mPosition的值为adapter中的最新值。
    private void applyAdd(UpdateOp op) {
        postponeAndUpdateViewHolders(op);
    }

    // 更新的是holder中的变量mPosition的值。修改的是受当前操作影响的holder的值，而不是操作本身的holder
    private void postponeAndUpdateViewHolders(UpdateOp op) {
        if (DEBUG) {
            Log.d(TAG, "postponing " + op);
        }
        // 从mPendingUpdates中删除到加入mPostponedList，这中间只是对ops做了重排序，去掉了无效、重复的操作。
        mPostponedList.add(op);
        // 下面的方法就是根据具体的op类型和参数，将holder中的mPosition更新到最新的位置。
        switch (op.cmd) {
            case UpdateOp.ADD:
                mCallback.offsetPositionsForAdd(op.positionStart, op.itemCount);
                break;
            case UpdateOp.MOVE:
                mCallback.offsetPositionsForMove(op.positionStart, op.itemCount);
                break;
            case UpdateOp.REMOVE:
                mCallback.offsetPositionsForRemovingLaidOutOrNewView(op.positionStart,
                        op.itemCount);
                break;
            case UpdateOp.UPDATE:
                mCallback.markViewHoldersUpdated(op.positionStart, op.itemCount, op.payload);
                break;
            default:
                throw new IllegalArgumentException("Unknown update op type for " + op);
        }
    }

    boolean hasPendingUpdates() {
        return mPendingUpdates.size() > 0;
    }

    int findPositionOffset(int position) {
        return findPositionOffset(position, 0);
    }

    int findPositionOffset(int position, int firstPostponedItem) {
        int count = mPostponedList.size();
        for (int i = firstPostponedItem; i < count; ++i) {
            UpdateOp op = mPostponedList.get(i);
            if (op.cmd == UpdateOp.MOVE) {
                if (op.positionStart == position) {
                    position = op.itemCount;
                } else {
                    if (op.positionStart < position) {
                        position--; // like a remove
                    }
                    if (op.itemCount <= position) {
                        position++; // like an add
                    }
                }
            } else if (op.positionStart <= position) {
                if (op.cmd == UpdateOp.REMOVE) {
                    if (position < op.positionStart + op.itemCount) {
                        return -1;
                    }
                    position -= op.itemCount;
                } else if (op.cmd == UpdateOp.ADD) {
                    position += op.itemCount;
                }
            }
        }
        return position;
    }

    /**
     * @return True if updates should be processed.
     */
    boolean onItemRangeChanged(int positionStart, int itemCount, Object payload) {
        mPendingUpdates.add(obtainUpdateOp(UpdateOp.UPDATE, positionStart, itemCount, payload));
        return mPendingUpdates.size() == 1;
    }

    /**
     * @return True if updates should be processed.
     */
    boolean onItemRangeInserted(int positionStart, int itemCount) {
        mPendingUpdates.add(obtainUpdateOp(UpdateOp.ADD, positionStart, itemCount, null));
        // size等于1的时候，说明是之前没有改变，那么就需要返回true，告诉外部调用requestLayout()。
        // 如果size不等于1，说明已经有改变通知到RecyclerView，即调用过requestlayout()，不需要
        // 再次调用
        return mPendingUpdates.size() == 1;
    }

    /**
     * @return True if updates should be processed.
     */
    boolean onItemRangeRemoved(int positionStart, int itemCount) {
        mPendingUpdates.add(obtainUpdateOp(UpdateOp.REMOVE, positionStart, itemCount, null));
        return mPendingUpdates.size() == 1;
    }

    /**
     * @return True if updates should be processed.
     */
    boolean onItemRangeMoved(int from, int to, int itemCount) {
        if (from == to) {
            return false;//no-op
        }
        if (itemCount != 1) {
            throw new IllegalArgumentException("Moving more than 1 item is not supported yet");
        }
        mPendingUpdates.add(obtainUpdateOp(UpdateOp.MOVE, from, to, null));
        return mPendingUpdates.size() == 1;
    }

    /**
     * Skips pre-processing and applies all updates in one pass.
     * 将ViewHolder直接调整为最终的状态
     */
    void consumeUpdatesInOnePass() {
        // we still consume postponed updates (if there is) in case there was a pre-process call
        // w/o a matching consumePostponedUpdates.
        consumePostponedUpdates();
        final int count = mPendingUpdates.size();
        for (int i = 0; i < count; i++) {
            UpdateOp op = mPendingUpdates.get(i);
            switch (op.cmd) {
                case UpdateOp.ADD:
                    // onDispatchSecondPass只是一个生命周期回调
                    mCallback.onDispatchSecondPass(op);
                    mCallback.offsetPositionsForAdd(op.positionStart, op.itemCount);
                    break;
                case UpdateOp.REMOVE:
                    mCallback.onDispatchSecondPass(op);
                    mCallback.offsetPositionsForRemovingInvisible(op.positionStart, op.itemCount);
                    break;
                case UpdateOp.UPDATE:
                    mCallback.onDispatchSecondPass(op);
                    mCallback.markViewHoldersUpdated(op.positionStart, op.itemCount, op.payload);
                    break;
                case UpdateOp.MOVE:
                    mCallback.onDispatchSecondPass(op);
                    mCallback.offsetPositionsForMove(op.positionStart, op.itemCount);
                    break;
            }
            if (mOnItemProcessedCallback != null) {
                mOnItemProcessedCallback.run();
            }
        }
        recycleUpdateOpsAndClearList(mPendingUpdates);
    }

    public int applyPendingUpdatesToPosition(int position) {
        final int size = mPendingUpdates.size();
        for (int i = 0; i < size; i ++) {
            UpdateOp op = mPendingUpdates.get(i);
            switch (op.cmd) {
                case UpdateOp.ADD:
                    if (op.positionStart <= position) {
                        position += op.itemCount;
                    }
                    break;
                case UpdateOp.REMOVE:
                    if (op.positionStart <= position) {
                        final int end = op.positionStart + op.itemCount;
                        if (end > position) {
                            return RecyclerView.NO_POSITION;
                        }
                        position -= op.itemCount;
                    }
                    break;
                case UpdateOp.MOVE:
                    if (op.positionStart == position) {
                        position = op.itemCount;//position end
                    } else {
                        if (op.positionStart < position) {
                            position -= 1;
                        }
                        if (op.itemCount <= position) {
                            position += 1;
                        }
                    }
                    break;
            }
        }
        return position;
    }

    /**
     * Queued operation to happen when child views are updated.
     */
    static class UpdateOp {

        static final int ADD = 0;

        static final int REMOVE = 1;

        static final int UPDATE = 2;

        static final int MOVE = 3;

        static final int POOL_SIZE = 30;

        int cmd;

        int positionStart;

        Object payload;

        // 对于正常操作，itemCount表示本次操作涉及的item的数量。比如insertItem，positionStart表示插入的
        // item的位置，itemCount=1，表示只有一个item需要操作。RecyclerView.Adapter.notifyItemInserted(int)
        //
        // holds the target position if this is a MOVE
        int itemCount;

        UpdateOp(int cmd, int positionStart, int itemCount, Object payload) {
            this.cmd = cmd;
            this.positionStart = positionStart;
            this.itemCount = itemCount;
            this.payload = payload;
        }

        String cmdToString() {
            switch (cmd) {
                case ADD:
                    return "add";
                case REMOVE:
                    return "rm";
                case UPDATE:
                    return "up";
                case MOVE:
                    return "mv";
            }
            return "??";
        }

        @Override
        public String toString() {
            return Integer.toHexString(System.identityHashCode(this))
                    + "[" + cmdToString() + ",s:" + positionStart + "c:" + itemCount
                    +",p:"+payload + "]";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            UpdateOp op = (UpdateOp) o;

            if (cmd != op.cmd) {
                return false;
            }
            if (cmd == MOVE && Math.abs(itemCount - positionStart) == 1) {
                // reverse of this is also true
                if (itemCount == op.positionStart && positionStart == op.itemCount) {
                    return true;
                }
            }
            if (itemCount != op.itemCount) {
                return false;
            }
            if (positionStart != op.positionStart) {
                return false;
            }
            if (payload != null) {
                if (!payload.equals(op.payload)) {
                    return false;
                }
            } else if (op.payload != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = cmd;
            result = 31 * result + positionStart;
            result = 31 * result + itemCount;
            return result;
        }
    }

    @Override
    public UpdateOp obtainUpdateOp(int cmd, int positionStart, int itemCount, Object payload) {
        UpdateOp op = mUpdateOpPool.acquire();
        if (op == null) {
            op = new UpdateOp(cmd, positionStart, itemCount, payload);
        } else {
            op.cmd = cmd;
            op.positionStart = positionStart;
            op.itemCount = itemCount;
            op.payload = payload;
        }
        return op;
    }

    @Override
    public void recycleUpdateOp(UpdateOp op) {
        if (!mDisableRecycler) {
            op.payload = null;
            mUpdateOpPool.release(op);
        }
    }

    void recycleUpdateOpsAndClearList(List<UpdateOp> ops) {
        final int count = ops.size();
        for (int i = 0; i < count; i++) {
            recycleUpdateOp(ops.get(i));
        }
        ops.clear();
    }

    /**
     * Contract between AdapterHelper and RecyclerView.
     *
     * insert：调整的是受insert影响的item的mPosition
     * move：调整了受影响的item，也调整了自己的mPosition
     */
    static interface Callback {

        ViewHolder findViewHolder(int position);

        void offsetPositionsForRemovingInvisible(int positionStart, int itemCount);

        void offsetPositionsForRemovingLaidOutOrNewView(int positionStart, int itemCount);

        void markViewHoldersUpdated(int positionStart, int itemCount, Object payloads);

        void onDispatchFirstPass(UpdateOp updateOp);

        void onDispatchSecondPass(UpdateOp updateOp);

        void offsetPositionsForAdd(int positionStart, int itemCount);

        void offsetPositionsForMove(int from, int to);
    }
}
