package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import org.ggp.base.player.gamer.statemachine.sancho.RoleOrdering;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;

public class MajorityCalculator
{
  protected ReversableGoalsCalculator          goalsCalculator = null;
  protected RoleOrdering                       roleOrdering = null;
  protected boolean                            reverseRoles = false;
  private boolean                              roleReversalFixed = false;
  protected boolean                            predictionsMatch = true;

  public void noteTerminalStateSample(ForwardDeadReckonInternalMachineState xiState,
                                      int[] xiRoleScores)
  {
    if ( predictionsMatch )
    {
      for (int i = 0; i < xiRoleScores.length; i++)
      {
        int predictedScore = goalsCalculator.getGoalValue(xiState, roleOrdering.roleIndexToRole(i));

        switch(predictedScore)
        {
          case 0:
            if ( xiRoleScores[i] == 0 )
            {
              if ( roleReversalFixed )
              {
                if ( reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
              }
            }
            else if ( xiRoleScores[i] == 100 )
            {
              if ( roleReversalFixed )
              {
                if ( !reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
                reverseRoles = true;
              }
            }
            else
            {
              predictionsMatch = false;
            }
            break;
          case 50:
            if ( xiRoleScores[i] != 50 )
            {
              predictionsMatch = false;
            }
            break;
          case 100:
            if ( xiRoleScores[i] == 100 )
            {
              if ( roleReversalFixed )
              {
                if ( reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
              }
            }
            else if ( xiRoleScores[i] == 0 )
            {
              if ( roleReversalFixed )
              {
                if ( !reverseRoles )
                {
                  predictionsMatch = false;
                }
              }
              else
              {
                roleReversalFixed = true;
                reverseRoles = true;
              }
            }
            else
            {
              predictionsMatch = false;
            }
            break;
          default:
            assert(false);
            break;
        }
      }
    }
  }
}
