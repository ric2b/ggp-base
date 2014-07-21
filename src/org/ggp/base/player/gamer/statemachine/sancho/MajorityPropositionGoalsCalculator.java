package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.GoalsCalculator;

/**
 * @author steve
 *  Goals calculator for games where the goal values are 0, 50 or 100 depending
 *  on which role has more of a proposition subset
 *  Only supports 2 player games currently
 */
public class MajorityPropositionGoalsCalculator implements GoalsCalculator
{
  private final Map<Role,ForwardDeadReckonInternalMachineState> roleMasks;
  private ForwardDeadReckonInternalMachineState stateBuffer = null;

  /**
   * Construct new goals calculator
   */
  public MajorityPropositionGoalsCalculator()
  {
    roleMasks = new HashMap<>();
  }

  /**
   * Construct a functional close of a specified instance that may be used in a thread safe
   * manner relative to the original to calculate goal values
   * @param master - master copy to instantiate a functional clone of
   */
  private MajorityPropositionGoalsCalculator(MajorityPropositionGoalsCalculator master)
  {
    roleMasks = master.roleMasks;
  }

  /**
   * Specify base propositions that are included in role count
   * @param role - role to add for
   * @param mask - mask of the pops to count for the specified role
   */
  public void addRoleMask(Role role, ForwardDeadReckonInternalMachineState mask)
  {
    roleMasks.put(role, mask);
  }

  @Override
  public int getGoalValue(ForwardDeadReckonInternalMachineState xiState,
                          Role xiRole)
  {
    assert(roleMasks.size()==2);

    int ourCount = 0;
    int theirCount = 0;

    for(Entry<Role, ForwardDeadReckonInternalMachineState> e : roleMasks.entrySet())
    {
      if ( stateBuffer == null )
      {
        stateBuffer = new ForwardDeadReckonInternalMachineState(xiState);
      }
      else
      {
        stateBuffer.copy(xiState);
      }

      stateBuffer.intersect(e.getValue());
      if ( xiRole.equals(e.getKey()))
      {
        ourCount += stateBuffer.size();
      }
      else
      {
        theirCount += stateBuffer.size();
      }
    }

    if ( ourCount > theirCount )
    {
      return 100;
    }
    if ( ourCount < theirCount )
    {
      return 0;
    }
    return 50;
  }

  @Override
  public GoalsCalculator createThreadSafeReference()
  {
    return new MajorityPropositionGoalsCalculator(this);
  }
}
