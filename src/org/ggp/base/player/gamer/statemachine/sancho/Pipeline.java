package org.ggp.base.player.gamer.statemachine.sancho;

/**
 * Highly efficient, lock-free rollout request pipeline.
 */
public class Pipeline
{
  final private SimplePipeline[] threadPipelines;
  private int nextExpandThread = -1;
  private int nextDrainThread = -1;

  /**
   * Create a pipeline with the specified maximum size.
   *
   * @param xiSize - the maximum number of objects that can be in the pipeline.
   * @param xiNumRoles number of roles in the game
   */
  public Pipeline(int xiSize, int xiNumRoles)
  {
    threadPipelines = new SimplePipeline[ThreadControl.ROLLOUT_THREADS];
    for (int i = 0; i < ThreadControl.ROLLOUT_THREADS; i++)
    {
      threadPipelines[i] = new SimplePipeline(xiSize, xiNumRoles);
    }
  }

  /**
   * @return whether the tree thread can perform an expansion now.
   */
  public boolean canExpand()
  {
    for (int i = 0; i < ThreadControl.ROLLOUT_THREADS; i++)
    {
      if (threadPipelines[i].canExpand())
      {
        return true;
      }
    }

    return false;
  }

  /**
   * @return a blank rollout request to be filled in.
   *
   * The caller must ensure (or know) that {@link #canExpand()} is true before calling this method.  After calling
   * this method, the caller must call {@link #expandComplete()}.
   */
  public RolloutRequest getNextExpandSlot()
  {
    do
    {
      nextExpandThread = (nextExpandThread + 1) % ThreadControl.ROLLOUT_THREADS;
    } while(!threadPipelines[nextExpandThread].canExpand());

    return threadPipelines[nextExpandThread].getNextExpandSlot();
  }

  /**
   * Publish a rollout request for further processing.
   */
  public void expandComplete()
  {
    threadPipelines[nextExpandThread].expandComplete();
  }

  /**
   * Get the next rollout request for the specified thread.
   *
   * This method will not return until a rollout request is available.
   *
   * @param xiThreadIndex - the thread making the request.
   *
   * @return the next rollout request.
   */
  public RolloutRequest getNextRolloutRequest(int xiThreadIndex)
  {
    return threadPipelines[xiThreadIndex].getNextRolloutRequest();
  }

  /**
   * Mark a rollout as complete.
   *
   * @param xiThreadIndex - the thread making the request.
   */
  public void rolloutComplete(int xiThreadIndex)
  {
    threadPipelines[xiThreadIndex].rolloutComplete();
  }

  /**
   * @return whether there are any completed rollout requests ready for back-propagation.
   */
  public boolean canBackPropagate()
  {
    for (int i = 0; i < ThreadControl.ROLLOUT_THREADS; i++)
    {
      if (threadPipelines[i].canBackPropagate())
      {
        return true;
      }
    }

    return false;
  }

  /**
   * @return the next rollout request to be back-propagated.
   *
   * This method will not return until a rollout request is available.
   */
  public RolloutRequest getNextRequestForBackPropagation()
  {
    int startDrainThread = -1;

    do
    {
      nextDrainThread = (nextDrainThread + 1) % ThreadControl.ROLLOUT_THREADS;
      if (startDrainThread == -1)
      {
        startDrainThread = nextDrainThread;
      }
      else if (startDrainThread == nextDrainThread)
      {
        Thread.yield();
      }
    } while (!threadPipelines[nextDrainThread].canBackPropagate());

    return threadPipelines[nextDrainThread].getNextRequestForBackPropagation();
  }

  /**
   * Mark that back-propagation of a request is complete.
   */
  public void backPropagationComplete()
  {
    threadPipelines[nextDrainThread].backPropagationComplete();
  }
}
