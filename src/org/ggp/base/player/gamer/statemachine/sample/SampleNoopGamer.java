
package org.ggp.base.player.gamer.statemachine.sample;

import org.ggp.base.apps.player.detail.DetailPanel;
import org.ggp.base.apps.player.detail.EmptyDetailPanel;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.statemachine.Move;

/**
 * SampleNoopGamer is a minimal gamer which always plays NOOP regardless of
 * which moves are actually legal in the current state of the game.
 */
public final class SampleNoopGamer extends SampleGamer
{
  @Override
  public Move stateMachineSelectMove(long timeout)
  {
    return new Move(GdlPool.getConstant("NOOP"));
  }

  @Override
  public DetailPanel getDetailPanel()
  {
    return new EmptyDetailPanel();
  }
}