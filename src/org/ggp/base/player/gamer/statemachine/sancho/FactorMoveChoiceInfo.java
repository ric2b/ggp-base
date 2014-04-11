package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.statemachine.Move;

public class FactorMoveChoiceInfo
{
  public double bestMoveValue;
  public Move   bestMove;
  public double pseudoNoopValue;
  public boolean bestMoveIsComplete;
  public boolean pseudoMoveIsComplete;
}
