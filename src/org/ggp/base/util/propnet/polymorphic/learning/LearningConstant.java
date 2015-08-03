
package org.ggp.base.util.propnet.polymorphic.learning;

import java.io.IOException;
import java.io.Writer;

import org.ggp.base.util.propnet.polymorphic.PolymorphicConstant;

/**
 * The Constant class is designed to represent nodes with fixed logical values.
 */
@SuppressWarnings("serial")
public final class LearningConstant extends LearningComponent implements
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
  public LearningConstant(boolean value)
  {
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

  @Override
  protected boolean getValueAndCost(EncapsulatedCost aggregatedCost)
  {
    aggregatedCost.incrementCost();

    return value;
  }

  @Override
  public String toString()
  {
    return Boolean.toString(value).toUpperCase();
  }

  @Override
  public void renderAsDot(Writer xiOutput) throws IOException
  {
    renderAsDot(xiOutput, "doublecircle", "grey", Boolean.toString(value).toUpperCase());
  }
}