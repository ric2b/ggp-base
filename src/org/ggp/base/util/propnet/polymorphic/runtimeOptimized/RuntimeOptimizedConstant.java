
package org.ggp.base.util.propnet.polymorphic.runtimeOptimized;

import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;

/**
 * The Constant class is designed to represent nodes with fixed logical values.
 */
@SuppressWarnings("serial")
public final class RuntimeOptimizedConstant extends RuntimeOptimizedComponent
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
  public RuntimeOptimizedConstant(int numOutputs, boolean value)
  {
    super(0, numOutputs);
    this.value = value;
  }

  /**
   * Returns the value that the constant was initialized to.
   * 
   * @see org.ggp.base.util.propnet.architecture.Component#getValueInternal()
   */
  @Override
  protected boolean getValueInternal()
  {
    return value;
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