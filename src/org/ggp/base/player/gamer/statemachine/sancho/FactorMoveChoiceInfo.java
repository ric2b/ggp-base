package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

public class FactorMoveChoiceInfo
{
  public double bestMoveValue;
  public TreeEdge bestEdge;
  public ForwardDeadReckonLegalMoveInfo   bestMove;
  public ForwardDeadReckonInternalMachineState resultingState;
  public double pseudoNoopValue;
  public boolean bestMoveIsComplete;
  public boolean pseudoMoveIsComplete;
}
