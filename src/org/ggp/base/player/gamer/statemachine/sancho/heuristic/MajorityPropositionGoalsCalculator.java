package org.ggp.base.player.gamer.statemachine.sancho.heuristic;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.ForwardDeadReckonPropnetStateMachine;
import org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon.GoalsCalculator;

/**
 * @author steve
 *  Goals calculator for games where the goal values are 0, 50 or 100 depending
 *  on which role has more of a proposition subset
 *  Only supports 2 player games currently
 */
public class MajorityPropositionGoalsCalculator extends MajorityCalculator
{
  private final Map<Role,ForwardDeadReckonInternalMachineState> roleMasks;
  private ForwardDeadReckonInternalMachineState stateBuffer = null;

  /**
   * Construct new goals calculator
   */
  protected MajorityPropositionGoalsCalculator(ForwardDeadReckonPropnetStateMachine xiStateMachine)
  {
    super(xiStateMachine);
    roleMasks = new HashMap<>();
  }

  /**
   * Construct a functional close of a specified instance that may be used in a thread safe
   * manner relative to the original to calculate goal values
   * @param master - master copy to instantiate a functional clone of
   */
  private MajorityPropositionGoalsCalculator(MajorityPropositionGoalsCalculator master)
  {
    super(master.stateMachine);
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
  public GoalsCalculator createThreadSafeReference()
  {
    return new MajorityPropositionGoalsCalculator(this);
  }

  /**
   * Swap the masks between the (must be 2) roles
   */
  @Override
  public void reverseRoles()
  {
    assert(roleMasks.size()==2);

    Role firstRole = null;
    Role secondRole = null;
    ForwardDeadReckonInternalMachineState firstMask = null;
    ForwardDeadReckonInternalMachineState secondMask = null;

    for(Entry<Role, ForwardDeadReckonInternalMachineState> e : roleMasks.entrySet())
    {
      if ( firstRole == null )
      {
        firstRole = e.getKey();
        firstMask = e.getValue();
      }
      else
      {
        secondRole = e.getKey();
        secondMask = e.getValue();
      }
    }

    roleMasks.clear();
    roleMasks.put(firstRole, secondMask);
    roleMasks.put(secondRole, firstMask);
  }

  @Override
  public String getName()
  {
    return "Proposition set cardinality";
  }

  @Override
  protected int getCount(ForwardDeadReckonInternalMachineState xiState,
                         Role xiRole)
  {
    if ( stateBuffer == null )
    {
      stateBuffer = new ForwardDeadReckonInternalMachineState(xiState);
    }
    else
    {
      stateBuffer.copy(xiState);
    }

    stateBuffer.intersect(roleMasks.get(xiRole));
    return (int)stateBuffer.size();
  }
}
