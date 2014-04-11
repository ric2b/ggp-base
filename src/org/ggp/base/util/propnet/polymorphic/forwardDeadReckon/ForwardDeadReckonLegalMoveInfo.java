
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.Factor;

public class ForwardDeadReckonLegalMoveInfo
{
  public Move                         move;
  public int                          roleIndex;
  public int                          masterIndex;
  public int                          globalMoveIndex;
  public ForwardDeadReckonProposition inputProposition;
  public Factor                       factor;
  public boolean                      isPseudoNoOp;

  public ForwardDeadReckonLegalMoveInfo()
  {
    isPseudoNoOp = false;
  }

  public ForwardDeadReckonLegalMoveInfo(boolean isPseudoNoOp)
  {
    this.isPseudoNoOp = isPseudoNoOp;
  }
}
