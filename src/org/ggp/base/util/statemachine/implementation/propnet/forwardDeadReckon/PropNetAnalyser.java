package org.ggp.base.util.statemachine.implementation.propnet.forwardDeadReckon;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ggp.base.util.propnet.polymorphic.PolymorphicComponent;
import org.ggp.base.util.propnet.polymorphic.PolymorphicPropNet;
import org.ggp.base.util.propnet.polymorphic.forwardDeadReckon.ForwardDeadReckonPropNet;
import org.ggp.base.util.propnet.polymorphic.tristate.TristatePropNet;
import org.ggp.base.util.propnet.polymorphic.tristate.TristateProposition;

/**
 * Static analysis for propnets.
 */
public class PropNetAnalyser
{
  private static final Logger LOGGER = LogManager.getLogger();

  private final ForwardDeadReckonPropNet mSourceNet;
  private final TristatePropNet mTristateNet;
  private final Map<PolymorphicComponent, PolymorphicComponent> mSourceToTarget;

  public PropNetAnalyser(ForwardDeadReckonPropNet xiSourceNet)
  {
    // Create a tri-state network to assist with the analysis.
    mSourceNet = xiSourceNet;
    mTristateNet = new TristatePropNet(mSourceNet);

    // Clone the mapping from source to target.
    mSourceToTarget = new HashMap<>(PolymorphicPropNet.sLastSourceToTargetMap.size());
    mSourceToTarget.putAll(PolymorphicPropNet.sLastSourceToTargetMap);
  }

  public void analyse(long xiTimeout)
  {
    // !! ARR Need to handle timeout.

    // Find the corollaries of setting each base proposition to true/false.
    for (PolymorphicComponent lSourceComp1 : mSourceNet.getBasePropositionsArray())
    {
      TristateProposition lTargetComp1 = (TristateProposition)mSourceToTarget.get(lSourceComp1);

      mTristateNet.reset();
      if (lTargetComp1.isLatch(true))
      {
        LOGGER.info(lTargetComp1.getName() + " is a positive latch");
      }

      mTristateNet.reset();
      if (lTargetComp1.isLatch(false))
      {
        LOGGER.info(lTargetComp1.getName() + " is a negative latch");
      }
    }
  }
}
