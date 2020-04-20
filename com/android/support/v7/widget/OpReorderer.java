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

/*
 * 这里只是对AdapterHelper的mPendingUpdates中的所有UpdateOp进行重排序，将所有move都移动到mPendingUpdates
 * 的队尾。在重排序的过程中，需要调整原本各个操作的positionStart和itemCount。OpReorderer主要做的就是前面
 * 两件事。
 *
 * 这个过程只会调整mPendingUpdates列表中元素的顺序和UpdateOp中变量的值，完全不会涉及到ViewHolder，所以也不
 * 存在对Holder的修改。
 *
 * 想要理解这个类的操作，主要需要基于以下前提：
 * 1. mPendingUpdates在做调整之前，里面的所有op，其positionStart和itemCount对应的数据位置和值，都是基于它
 * 前面的op执行结束后生成的全新的数据顺序确定的。（所以交换两个op的顺序才会需要调整positionStart和itemCount）
 *
 * add、remove、update之间的顺序并没有被修改，只是将所有move移动到了列表的尾端
 */
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
        // move操作对应的item，是否在remove执行之后，又回到了move前的起始位置。
        boolean revertedMove = false;

        // move的方向，是从小的positon移动到大的position（正向），还是从大的position移动到小的position（反向）
        final boolean moveIsBackwards;

        if (moveOp.positionStart < moveOp.itemCount) {
            // 从列表高处移动到列表低处
            moveIsBackwards = false;
            if (removeOp.positionStart == moveOp.positionStart
                    && removeOp.itemCount == moveOp.itemCount - moveOp.positionStart) {
                // move移动之后，对应的item实际上又被紧挨着的remove操作移除了。所以其实这个move没有意义。
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
            // 如果move的目标位置在remove的start之前，对调之后，remove的start需要减一，因为move没有执行，
            // remove对应的item实际前移了一个位置。
            removeOp.positionStart--;
        } else if (moveOp.itemCount < removeOp.positionStart + removeOp.itemCount) {
            // move is removed.
            // 如果move的item移动到目标位置之后，又被remove掉了，那么直接将move操作更新为remove操作，
            // 并且不需要接着再进行其它处理了，直接返回。
            removeOp.itemCount--;
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
            // move的起始位置小于remove的起始位置，对调之后，remove先发生，remove执行的时候move对应的item
            // 还在，所以remove的起始位置相当于后移了一个位置。
            removeOp.positionStart++;
        } else if (moveOp.positionStart < removeOp.positionStart + removeOp.itemCount) {
            // 如果move的item在remove的范围内，那么对调之后，相当于remove的几个item被劈成了两段，需要单独做
            // 移除操作。所以这里将remove分成了两段。
            final int remaining = removeOp.positionStart + removeOp.itemCount
                    - moveOp.positionStart;
            extraRm = mCallback.obtainUpdateOp(REMOVE, moveOp.positionStart + 1, remaining, null);
            removeOp.itemCount = moveOp.positionStart - removeOp.positionStart;
        }

        // if effects of move is reverted by remove, we are done.
        // 如果remove操作后，move操作相当于没有执行，那么交换顺序后，就没有必要再执行move。因为move和remove
        // 交换顺序后，move相当于从positionA移动到positionA。
        if (revertedMove) {
            list.set(movePos, removeOp);
            list.remove(removePos);
            mCallback.recycleUpdateOp(moveOp);
            return;
        }

        // now find out the new locations for move actions
        // 调换之后，先执行remove，那么move的start和end都需要前移
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
        // 后半段的remove先执行
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
            // 如果move的目标位置低于update的positionStart，那么调换执行顺序后，update的起始位置需要
            // 减1，填补move的空缺。
            updateOp.positionStart--;
        } else if (moveOp.itemCount < updateOp.positionStart + updateOp.itemCount) {
            // moved item is updated. add an update for it
            // 如果move的目标位置正好在update的范围内，那么按照正常的顺序，这个move的item在执行完move后，
            // 需要update。现在交换move和update，相当于move的item还没有移动到update的范围内，其他的item
            // 就已经update结束了，所以需要为这个move补充一个update操作。
            updateOp.itemCount--;
            extraUp1 = mCallback.obtainUpdateOp(UPDATE, moveOp.positionStart, 1, updateOp.payload);
        }
        // now affect of add is consumed. now apply effect of first remove
        if (moveOp.positionStart <= updateOp.positionStart) {
            // 如果move的起始位置在update前面，那么move和update对调之后，update发生时move的item还在原来的
            // 位置，相当于update的起始item的index比原本后移了一个位置。
            updateOp.positionStart++;
        } else if (moveOp.positionStart < updateOp.positionStart + updateOp.itemCount) {
            // move本来发生在update之前，所以update是连续的一段范围。现在先执行update，move还没有发生，
            // 相当于move把原本的update拆成了两段update，即updateOp和extraUp2
            final int remaining = updateOp.positionStart + updateOp.itemCount
                    - moveOp.positionStart;
            extraUp2 = mCallback.obtainUpdateOp(UPDATE, moveOp.positionStart + 1, remaining,
                    updateOp.payload);
            updateOp.itemCount -= remaining;
        }
        // 这里的对调位置，一定是相邻的位置发生交换，不会出现跨越某个item
        // 将move后移，放到update的位置
        list.set(update, moveOp);
        if (updateOp.itemCount > 0) {
            list.set(move, updateOp);
        } else {
            // 如果move和update操作的是同一个item，那么调整顺序后，原本的update就不存在了，只剩新增加
            // 的extraUp1，那么把原本的update移除
            list.remove(move);
            mCallback.recycleUpdateOp(updateOp);
        }

        // 原本update中move对应的item的单独的update，放到原本update之前执行。
        if (extraUp1 != null) {
            list.add(move, extraUp1);
        }
        // 被move拆成两段的update，position数值大的部分先执行。
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
