package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.Collection;
import java.util.Iterator;

import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonLegalMoveInfo;

public class FactorFilteredForwardDeadReckonLegalMoveSet implements Collection<ForwardDeadReckonLegalMoveInfo>
{
  static ForwardDeadReckonLegalMoveInfo pseudoNoOpMove = new ForwardDeadReckonLegalMoveInfo(true);

  private class FactorFilteredMoveIterator implements Iterator<ForwardDeadReckonLegalMoveInfo>
  {
    private Factor factor;
    private Iterator<ForwardDeadReckonLegalMoveInfo> wrapped;
    private ForwardDeadReckonLegalMoveInfo availableElement = null;
    private boolean includeForcedPseudoNoops;

    FactorFilteredMoveIterator(Factor factor, Iterator<ForwardDeadReckonLegalMoveInfo> wrapped, boolean includeForcedPseudoNoops)
    {
      this.factor = factor;
      this.wrapped = wrapped;
      this.includeForcedPseudoNoops = (includeForcedPseudoNoops && factor.getAlwaysIncludePseudoNoop());

      preFindNext();

      //  If the underlying set has no moves contained in this factor insert a pseudo-noop
      //  so that the factor game is well-formed
      if ( availableElement == null )
      {
        availableElement = pseudoNoOpMove;
      }
    }

    private void preFindNext()
    {
      while(wrapped.hasNext())
      {
        availableElement = wrapped.next();
        if ( availableElement.factor == factor || availableElement.factor == null )
        {
          return;
        }
      }

      if (includeForcedPseudoNoops && availableElement != pseudoNoOpMove)
      {
        availableElement = pseudoNoOpMove;
      }
      else
      {
        availableElement = null;
      }
    }

    @Override
    public boolean hasNext()
    {
      return (availableElement != null);
    }

    @Override
    public ForwardDeadReckonLegalMoveInfo next()
    {
      ForwardDeadReckonLegalMoveInfo result = availableElement;

      if ( result.inputProposition == null || result.factor == null )
      {
        //  We only ever add a pseudo-noop if there isn't a real one or
        //  other multi-factor move (which in practice is essentially going
        //  to be the same thing)
        includeForcedPseudoNoops = false;
      }

      preFindNext();
      return result;
    }

    @Override
    public void remove()
    {
      // Not supported
    }
  }

  private Collection<ForwardDeadReckonLegalMoveInfo> baseCollection;
  private Factor  factor;
  private boolean includeForcedPseudoNoops;

  public FactorFilteredForwardDeadReckonLegalMoveSet(Factor factor, Collection<ForwardDeadReckonLegalMoveInfo> wrapped, boolean includeForcedPseudoNoops)
  {
    baseCollection = wrapped;
    this.factor = factor;
    this.includeForcedPseudoNoops = includeForcedPseudoNoops;
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
    return !(new FactorFilteredMoveIterator(factor, baseCollection.iterator(), includeForcedPseudoNoops)).hasNext();
  }

  @Override
  public Iterator<ForwardDeadReckonLegalMoveInfo> iterator()
  {
    return new FactorFilteredMoveIterator(factor, baseCollection.iterator(), includeForcedPseudoNoops);
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
     int count = 0;

     for(@SuppressWarnings("unused") ForwardDeadReckonLegalMoveInfo x : this)
     {
       count++;
     }

     return count;
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
