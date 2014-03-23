
package org.ggp.base.util.propnet.polymorphic.forwardDeadReckon;

import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;

/**
 * The Constant class is designed to represent nodes with fixed logical values.
 */
@SuppressWarnings("serial")
public final class ForwardDeadReckonConstant extends
                                            ForwardDeadReckonComponent
                                                                      implements
                                                                      PolymorphicConstant
{
  /** The value of the constant. */
  private final boolean value;

  /**
   * Creates a new Constant with value <tt>value</tt>.
   * 
   * @param value
   *          The value of the Constant.
   */
  public ForwardDeadReckonConstant(int numOutputs, boolean value)
  {
    super(0, numOutputs);
    this.value = value;
  }

  /**
   * Gets the value of the Component.
   * 
   * @return The value of the Component.
   */
  @Override
  public boolean getValue()
  {
    return value;
  }

  @Override
  public void setKnownChangedState(boolean newState,
                                   int instanceId,
                                   ForwardDeadReckonComponent source)
  {
  }

  @Override
  public void reset(int instanceId)
  {
    super.reset(instanceId);
    cachedValue[instanceId] = value;
  }

  /**
   * @see org.ggp.base.util.propnet.architecture.Component#toString()
   */
  @Override
  public String toString()
  {
    return toDot("doublecircle", "grey", Boolean.toString(value).toUpperCase());
  }
}