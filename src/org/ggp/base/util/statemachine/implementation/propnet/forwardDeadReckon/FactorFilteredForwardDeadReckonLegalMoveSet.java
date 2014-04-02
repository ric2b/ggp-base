package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.Iterator;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

public class FactorFilteredForwardDeadReckonLegalMoveSet implements Collection<ForwardDeadReckonLegalMoveInfo>
{
  private Collection<ForwardDeadReckonLegalMoveInfo> baseCollection;
  private Factor  factor;

  public FactorFilteredForwardDeadReckonLegalMoveSet(Factor factor, Collection<ForwardDeadReckonLegalMoveInfo> wrapped)
  {
    baseCollection = wrapped;
    this.factor = factor;
  }

  @Override
  public boolean add(ForwardDeadReckonLegalMoveInfo xiArg0)
  {
    // Not supported - this is a read-only collection
    return false;
  }

  @Override
  public boolean addAll(Collection<? extends ForwardDeadReckonLegalMoveInfo> xiArg0)
  {
    // Not supported - this is a read-only collection
    return false;
  }

  @Override
  public void clear()
  {
    // Not supported - this is a read-only collection
  }

  @Override
  public boolean contains(Object o)
  {
     return baseCollection.contains(o);
  }

  @Override
  public boolean containsAll(Collection<?> c)
  {
    return baseCollection.containsAll(c);
  }

  @Override
  public boolean isEmpty()
  {
    //  TODO - this needs to filter
    return baseCollection.isEmpty();
  }

  @Override
  public Iterator<ForwardDeadReckonLegalMoveInfo> iterator()
  {
    //  TODO - this needs to filter
    return baseCollection.iterator();
  }

  @Override
  public boolean remove(Object xiArg0)
  {
    // Not supported - this is a read-only collection
    return false;
  }

  @Override
  public boolean removeAll(Collection<?> xiArg0)
  {
    // Not supported - this is a read-only collection
    return false;
  }

  @Override
  public boolean retainAll(Collection<?> xiArg0)
  {
    // Not supported - this is a read-only collection
    return false;
  }

  @Override
  public int size()
  {
    //  TODO - this needs to filter
    return baseCollection.size();
  }

  @Override
  public Object[] toArray()
  {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public <T> T[] toArray(T[] xiArg0)
  {
    // TODO Auto-generated method stub
    return null;
  }

}
