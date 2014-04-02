package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonInternalMachineState;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonProposition;

/**
 * @author steve
 *
 * Class representing a factor within a game's propnet.  A factor
 * is a partition within a partitioning of the base propositions into
 * disjoint sets between which there are no causative logical connections
 * or coupling via terminal/goal conditions
 */
public class Factor
{
  private Set<PolymorphicComponent> components = new HashSet<>();
  private ForwardDeadReckonInternalMachineState stateMask = null;
  private ForwardDeadReckonPropnetStateMachine stateMachine;

  public Factor(ForwardDeadReckonPropnetStateMachine stateMachine)
  {
    this.stateMachine = stateMachine;
  }

  public boolean containsComponent(PolymorphicComponent c)
  {
    return components.contains(c);
  }

  public void addComponent(PolymorphicComponent c)
  {
    components.add(c);
  }

  public Set<PolymorphicComponent> getComponents()
  {
    return components;
  }

  public boolean containsAny(Collection<? extends PolymorphicComponent> toTest)
  {
    for(PolymorphicComponent c : toTest)
    {
      if ( components.contains(c))
      {
        return true;
      }
    }

    return false;
  }

  public void addAll(Collection<? extends PolymorphicComponent> toAdd)
  {
    components.addAll(toAdd);
  }

  public void dump()
  {
    for(PolymorphicComponent c : components)
    {
      if ( c instanceof PolymorphicProposition )
      {
        PolymorphicProposition p = (PolymorphicProposition)c;

        System.out.println("  " + p.getName());
      }
    }
  }

  public ForwardDeadReckonInternalMachineState getStateMask()
  {
    if ( stateMask == null )
    {
      stateMask = new ForwardDeadReckonInternalMachineState(stateMachine.getInfoSet());
      for(PolymorphicProposition p : stateMachine.getFullPropNet().getBasePropositions().values())
      {
        ForwardDeadReckonProposition fdrp = (ForwardDeadReckonProposition)p;
        ForwardDeadReckonPropositionCrossReferenceInfo info = (ForwardDeadReckonPropositionCrossReferenceInfo)fdrp.getInfo();

        if ( info.factor == this || info.factor == null )
        {
          stateMask.add(info);
        }
      }
    }

    return stateMask;
  }
}
