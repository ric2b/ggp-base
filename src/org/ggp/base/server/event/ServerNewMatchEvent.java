
package org.ggp.base.server.event;

import java.io.Serializable;

import org.ggp.base.util.observer.Event;
import org.ggp.base.util.statemachine.MachineState;
import org.ggp.base.util.statemachine.Role;


@SuppressWarnings("serial")
public final class ServerNewMatchEvent extends Event implements Serializable
{

  private final Role[]       roles;
  private final MachineState initialState;

  public ServerNewMatchEvent(Role[] roles, MachineState initialState)
  {
    this.roles = roles;
    this.initialState = initialState;
  }

  public Role[] getRoles()
  {
    return roles;
  }

  public MachineState getInitialState()
  {
    return initialState;
  }

}
