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

import java.util.List;

import android.support.v7.widget.AdapterHelper.UpdateOp;
import static android.support.v7.widget.AdapterHelper.UpdateOp.ADD;
import static android.support.v7.widget.AdapterHelper.UpdateOp.MOVE;
import static android.support.v7.widget.AdapterHelper.UpdateOp.REMOVE;
import static android.support.v7.widget.AdapterHelper.UpdateOp.UPDATE;

// 这里只是对AdapterHelper的mPendingUpdates中的所有UpdateOp进行重排序等操作，从而得到一个去除冗余操作的
// mPendingUpdates列表。过程中会更新UpdateOp中的变量，但是这里完全没有涉及到ViewHolder，所以也不存在对
// Holder的修改。
class OpReorderer {

    final Callback mCallback;

    public OpReorderer(Callback callback) {
        mCallback = callback;
    }

    void reorderOps(List<UpdateOp> ops) {
        // since move operations breaks continuity, their effects on ADD/RM are hard to handle.
        // we push them to the end of the list so that they can be handled easily.
        int badMove;
        // 假如list中是 move add1 add2，首先调换的是move和add1，与相邻的op对调
        while ((badMove = getLastMoveOutOfOrder(ops)) != -1) {
            swapMoveOp(ops, badMove, badMove + 1);
        }
    }

    /*
     * 1.一个item在remove之后不会再出现move的情形，除非remove之后又add了
     * 2.注意，move是要调整到add、remove、update之后的，也就是说在三者之后执行，所以所有swap的逻辑都基于
     *   这个前提。
     */
    private void swapMoveOp(List<UpdateOp> list, int badMove, int next) {
        final UpdateOp moveOp = list.get(badMove);
        final UpdateOp nextOp = list.get(next);
        switch (nextOp.cmd) {
            case REMOVE:
                swapMoveRemove(list, badMove, moveOp, next, nextOp);
                break;
            case ADD:
                swapMoveAdd(list, badMove, moveOp, next, nextOp);
                break;
            case UPDATE:
                swapMoveUpdate(list, badMove, moveOp, next, nextOp);
                break;
        }
    }

    void swapMoveRemove(List<UpdateOp> list, int movePos, UpdateOp moveOp,
            int removePos, UpdateOp removeOp) {
        UpdateOp extraRm = null;
        // check if move is nulled out by remove
        boolean revertedMove = false;
        final boolean moveIsBackwards;

        // 从列表上方移动到列表下方
        if (moveOp.positionStart < moveOp.itemCount) {
            moveIsBackwards = false;
            if (removeOp.positionStart == moveOp.positionStart
                    && removeOp.itemCount == moveOp.itemCount - moveOp.positionStart) {
                revertedMove = true;
            }
        } else {
            moveIsBackwards = true;
            if (removeOp.positionStart == moveOp.itemCount + 1 &&
                    removeOp.itemCount == moveOp.positionStart - moveOp.itemCount) {
                revertedMove = true;
            }
        }

        // going in reverse, first revert the effect of add
        if (moveOp.itemCount < removeOp.positionStart) {
            removeOp.positionStart--;
        } else if (moveOp.itemCount < removeOp.positionStart + removeOp.itemCount) {
            // move is removed.
            removeOp.itemCount --;
            moveOp.cmd = REMOVE;
            moveOp.itemCount = 1;
            if (removeOp.itemCount == 0) {
                list.remove(removePos);
                mCallback.recycleUpdateOp(removeOp);
            }
            // no need to swap, it is already a remove
            return;
        }

        // now affect of add is consumed. now apply effect of first remove
        if (moveOp.positionStart <= removeOp.positionStart) {
            removeOp.positionStart++;
        } else if (moveOp.positionStart < removeOp.positionStart + removeOp.itemCount) {
            final int remaining = removeOp.positionStart + removeOp.itemCount
                    - moveOp.positionStart;
            extraRm = mCallback.obtainUpdateOp(REMOVE, moveOp.positionStart + 1, remaining, null);
            removeOp.itemCount = moveOp.positionStart - removeOp.positionStart;
        }

        // if effects of move is reverted by remove, we are done.
        if (revertedMove) {
            list.set(movePos, removeOp);
            list.remove(removePos);
            mCallback.recycleUpdateOp(moveOp);
            return;
        }

        // now find out the new locations for move actions
        if (moveIsBackwards) {
            if (extraRm != null) {
                if (moveOp.positionStart > extraRm.positionStart) {
                    moveOp.positionStart -= extraRm.itemCount;
                }
                if (moveOp.itemCount > extraRm.positionStart) {
                    moveOp.itemCount -= extraRm.itemCount;
                }
            }
            if (moveOp.positionStart > removeOp.positionStart) {
                moveOp.positionStart -= removeOp.itemCount;
            }
            if (moveOp.itemCount > removeOp.positionStart) {
                moveOp.itemCount -= removeOp.itemCount;
            }
        } else {
            if (extraRm != null) {
                if (moveOp.positionStart >= extraRm.positionStart) {
                    moveOp.positionStart -= extraRm.itemCount;
                }
                if (moveOp.itemCount >= extraRm.positionStart) {
                    moveOp.itemCount -= extraRm.itemCount;
                }
            }
            if (moveOp.positionStart >= removeOp.positionStart) {
                moveOp.positionStart -= removeOp.itemCount;
            }
            if (moveOp.itemCount >= removeOp.positionStart) {
                moveOp.itemCount -= removeOp.itemCount;
            }
        }

        list.set(movePos, removeOp);
        if (moveOp.positionStart != moveOp.itemCount) {
            list.set(removePos, moveOp);
        } else {
            list.remove(removePos);
        }
        if (extraRm != null) {
            list.add(movePos, extraRm);
        }
    }

    private void swapMoveAdd(List<UpdateOp> list, int move, UpdateOp moveOp, int add,
            UpdateOp addOp) {
        int offset = 0;
        // going in reverse, first revert the effect of add
        // moveOp.itemCount是positionEnd
        // 交换前，这个add是基于move操作执行完成后的数据索引确定的positionStart。如果move的目标位置在
        // add.positionStart之前，那么交换后，相当于add先执行，positionStart自然需要减一，因为此时
        // move的item还没有占据位置。
        if (moveOp.itemCount < addOp.positionStart) {
            offset--;
        }
        // 同样的道理，如果交换前，move的positionStart小于add.positionStart，交换后，add.positionStart
        // 是在move没有进行的前提下确定的，所以需要比move先执行时加一。
        if (moveOp.positionStart < addOp.positionStart) {
            offset++;
        }
        // 根据add操作的位置，更新move操作的位置
        if (addOp.positionStart <= moveOp.positionStart) {
            moveOp.positionStart += addOp.itemCount;
        }
        if (addOp.positionStart <= moveOp.itemCount) {
            moveOp.itemCount += addOp.itemCount;
        }
        addOp.positionStart += offset;
        // 调换两个op的位置
        list.set(move, addOp);
        list.set(add, moveOp);
    }

    void swapMoveUpdate(List<UpdateOp> list, int move, UpdateOp moveOp, int update,
            UpdateOp updateOp) {
        UpdateOp extraUp1 = null;
        UpdateOp extraUp2 = null;
        // going in reverse, first revert the effect of add
        if (moveOp.itemCount < updateOp.positionStart) {
            updateOp.positionStart--;
        } else if (moveOp.itemCount < updateOp.positionStart + updateOp.itemCount) {
            // moved item is updated. add an update for it
            updateOp.itemCount--;
            extraUp1 = mCallback.obtainUpdateOp(UPDATE, moveOp.positionStart, 1, updateOp.payload);
        }
        // now affect of add is consumed. now apply effect of first remove
        if (moveOp.positionStart <= updateOp.positionStart) {
            updateOp.positionStart++;
        } else if (moveOp.positionStart < updateOp.positionStart + updateOp.itemCount) {
            final int remaining = updateOp.positionStart + updateOp.itemCount
                    - moveOp.positionStart;
            extraUp2 = mCallback.obtainUpdateOp(UPDATE, moveOp.positionStart + 1, remaining,
                    updateOp.payload);
            updateOp.itemCount -= remaining;
        }
        list.set(update, moveOp);
        if (updateOp.itemCount > 0) {
            list.set(move, updateOp);
        } else {
            list.remove(move);
            mCallback.recycleUpdateOp(updateOp);
        }
        if (extraUp1 != null) {
            list.add(move, extraUp1);
        }
        if (extraUp2 != null) {
            list.add(move, extraUp2);
        }
    }

    // 动画允许的执行顺序必须是所有的move在列表的最后连续排列，即 add,remove,move,move。不允许出现
    // move后面还有add、remove等情况。
    // 这个方法从后向前遍历，找到第一个后面存在add等操作的move。
    private int getLastMoveOutOfOrder(List<UpdateOp> list) {
        boolean foundNonMove = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            final UpdateOp op1 = list.get(i);
            if (op1.cmd == MOVE) {
                if (foundNonMove) {
                    return i;
                }
            } else {
                foundNonMove = true;
            }
        }
        return -1;
    }

    static interface Callback {

        UpdateOp obtainUpdateOp(int cmd, int startPosition, int itemCount, Object payload);

        void recycleUpdateOp(UpdateOp op);
    }
}
