package org.ggp.base.util.propnet.polymorphic.tristate;

import java.io.Writer;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;

public abstract class TristateComponent implements PolymorphicComponent
{
  public enum Tristate
  {
    TRUE,
    FALSE,
    UNKNOWN;
  }

  protected final TristatePropNet mParent;

  private final Set<TristateComponent> mInputs = new HashSet<>();
  private final Set<TristateComponent> mOutputs = new HashSet<>();

  protected Tristate mValue;
  protected int mNumUnknownInputs;

  //////////////////////////////////////////////////
  // Network use methods.
  //////////////////////////////////////////////////
  public void reset()
  {
    mValue = Tristate.UNKNOWN;
    mNumUnknownInputs = mInputs.size();
  }

  /**
   * Inform a component that one of its inputs has changed (from UNKNOWN to the value specified here).
   *
   * @param xiNewValue - the new value, either TRUE or FALSE.
   */
  public abstract void changeInput(Tristate xiNewValue);

  /**
   * Propagate a change in output state through the network.  Components MUST NOT call this method unless their output
   * has actually changed.  Components must set mValue before calling this method.
   */
  protected void changeOutput()
  {
    for (TristateComponent lOutput : mOutputs)
    {
      lOutput.changeInput(mValue);
    }
  }

  //////////////////////////////////////////////////
  // Network construction methods.
  //////////////////////////////////////////////////
  protected TristateComponent(TristatePropNet xiNetwork)
  {
    mParent = xiNetwork;
  }

  @Override
  public void addInput(PolymorphicComponent xiInput)
  {
    mInputs.add((TristateComponent)xiInput);
  }

  @Override
  public void addOutput(PolymorphicComponent xiOutput)
  {
    mOutputs.add((TristateComponent)xiOutput);
  }

  @Override
  public Collection<? extends PolymorphicComponent> getInputs()
  {
    return mInputs;
  }

  @Override
  public PolymorphicComponent getSingleInput()
  {
    return mInputs.iterator().next();
  }

  @Override
  public Collection<? extends PolymorphicComponent> getOutputs()
  {
    return mOutputs;
  }

  @Override
  public PolymorphicComponent getSingleOutput()
  {
    return mOutputs.iterator().next();
  }

  @Override
  public boolean getValue()
  {
    throw new RuntimeException("Can't call getValue() on tri-state component - use getTristateValue");
  }

  public Tristate getTristateValue()
  {
    return mValue;
  }

  @Override
  public void renderAsDot(Writer xiOutput)
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public void removeInput(PolymorphicComponent xiInput)
  {
    mInputs.remove(xiInput);
  }

  @Override
  public void removeAllInputs()
  {
    mInputs.clear();
  }

  @Override
  public void removeOutput(PolymorphicComponent xiOutput)
  {
    mOutputs.remove(xiOutput);
  }

  @Override
  public void removeAllOutputs()
  {
    mOutputs.clear();
  }

  @Override
  public void crystalize()
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public void setSignature(long xiSignature)
  {
    throw new RuntimeException("Not implemented");
  }

  @Override
  public long getSignature()
  {
    throw new RuntimeException("Not implemented");
  }
}
