package org.ggp.base.util.propnet.polymorphic.tristate;

import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;

public class TristatePropNet extends PolymorphicPropNet
{
  /**
   * Create a tri-state propnet from another propnet (of any kind).
   *
   * @param xiSourcePropnet - the source
   */
  public TristatePropNet(PolymorphicPropNet xiSourcePropnet)
  {
    super(xiSourcePropnet, new TristateComponentFactory());
  }

  public void reset()
  {
    // Reset all components.
    for (PolymorphicComponent lComponent : getComponents())
    {
      ((TristateComponent)lComponent).reset();
    }
  }
}
