package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicProposition;

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
}
