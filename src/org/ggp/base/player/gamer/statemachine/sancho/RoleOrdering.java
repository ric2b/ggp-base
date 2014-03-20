package org.ggp.base.player.gamer.statemachine.sancho;

import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.statemachine.StateMachine;

public class RoleOrdering
{
  //  Array of roles reordered so our role is first
  private Role[] reorderedRoles               = null;
  private Move[] canonicallyOrderedMoveBuffer = null;
  private int[]  roleOrderMap                 = null;

  public RoleOrdering(StateMachine underlyingStateMachine, Role ourRole)
  {
    int numRoles = underlyingStateMachine.getRoles().size();
    reorderedRoles = new Role[numRoles];
    roleOrderMap = new int[numRoles];
    canonicallyOrderedMoveBuffer = new Move[numRoles];

    reorderedRoles[0] = ourRole;

    int roleIndex = 1;
    int rawRoleIndex = 0;
    for (Role role : underlyingStateMachine.getRoles())
    {
      if (role.equals(ourRole))
      {
        roleOrderMap[0] = rawRoleIndex;
      }
      else
      {
        roleOrderMap[roleIndex] = rawRoleIndex;
        reorderedRoles[roleIndex++] = role;
      }
      rawRoleIndex++;
    }
  }

  public Role roleIndexToRole(int roleIndex)
  {
    return reorderedRoles[roleIndex];
  }

  public int roleIndexToRawRoleIndex(int roleIndex)
  {
    return roleOrderMap[roleIndex];
  }
}
