/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spark.deploy.yarn

import spark.{Logging, Utils}
import spark.scheduler.SplitInfo
import scala.collection
import org.apache.hadoop.yarn.api.records.{AMResponse, ApplicationAttemptId, ContainerId, Priority, Resource, ResourceRequest, ContainerStatus, Container}
import spark.scheduler.cluster.{ClusterScheduler, StandaloneSchedulerBackend}
import org.apache.hadoop.yarn.api.protocolrecords.{AllocateRequest, AllocateResponse}
import org.apache.hadoop.yarn.util.{RackResolver, Records}
import java.util.concurrent.{CopyOnWriteArrayList, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicInteger
import org.apache.hadoop.yarn.api.AMRMProtocol
import collection.JavaConversions._
import collection.mutable.{ArrayBuffer, HashMap, HashSet}
import org.apache.hadoop.conf.Configuration
import java.util.{Collections, Set => JSet}
import java.lang.{Boolean => JBoolean}

object AllocationType extends Enumeration ("HOST", "RACK", "ANY") {
  type AllocationType = Value
  val HOST, RACK, ANY = Value
}

// too many params ? refactor it 'somehow' ?
// needs to be mt-safe
// Need to refactor this to make it 'cleaner' ... right now, all computation is reactive : should make it 
// more proactive and decoupled.
// Note that right now, we assume all node asks as uniform in terms of capabilities and priority
// Refer to http://developer.yahoo.com/blogs/hadoop/posts/2011/03/mapreduce-nextgen-scheduler/ for more info
// on how we are requesting for containers.
private[yarn] class YarnAllocationHandler(val conf: Configuration, val resourceManager: AMRMProtocol, 
                                          val appAttemptId: ApplicationAttemptId,
                                          val maxWorkers: Int, val workerMemory: Int, val workerCores: Int,
                                          val preferredHostToCount: Map[String, Int], 
                                          val preferredRackToCount: Map[String, Int])
  extends Logging {


  // These three are locked on allocatedHostToContainersMap. Complementary data structures
  // allocatedHostToContainersMap : containers which are running : host, Set<containerid>
  // allocatedContainerToHostMap: container to host mapping
  private val allocatedHostToContainersMap = new HashMap[String, collection.mutable.Set[ContainerId]]()
  private val allocatedContainerToHostMap = new HashMap[ContainerId, String]()
  // allocatedRackCount is populated ONLY if allocation happens (or decremented if this is an allocated node)
  // As with the two data structures above, tightly coupled with them, and to be locked on allocatedHostToContainersMap
  private val allocatedRackCount = new HashMap[String, Int]()

  // containers which have been released.
  private val releasedContainerList = new CopyOnWriteArrayList[ContainerId]()
  // containers to be released in next request to RM
  private val pendingReleaseContainers = new ConcurrentHashMap[ContainerId, Boolean]

  private val numWorkersRunning = new AtomicInteger()
  // Used to generate a unique id per worker
  private val workerIdCounter = new AtomicInteger()
  private val lastResponseId = new AtomicInteger()

  def getNumWorkersRunning: Int = numWorkersRunning.intValue


  def isResourceConstraintSatisfied(container: Container): Boolean = {
    container.getResource.getMemory >= (workerMemory + YarnAllocationHandler.MEMORY_OVERHEAD)
  }

  def allocateContainers(workersToRequest: Int) {
    // We need to send the request only once from what I understand ... but for now, not modifying this much.

    // Keep polling the Resource Manager for containers
    val amResp = allocateWorkerResources(workersToRequest).getAMResponse

    val _allocatedContainers = amResp.getAllocatedContainers()
    if (_allocatedContainers.size > 0) {


      logDebug("Allocated " + _allocatedContainers.size + " containers, current count " + 
        numWorkersRunning.get() + ", to-be-released " + releasedContainerList + 
        ", pendingReleaseContainers : " + pendingReleaseContainers)
      logDebug("Cluster Resources: " + amResp.getAvailableResources)

      val hostToContainers = new HashMap[String, ArrayBuffer[Container]]()

      // ignore if not satisfying constraints      {
      for (container <- _allocatedContainers) {
        if (isResourceConstraintSatisfied(container)) {
          // allocatedContainers += container

          val host = container.getNodeId.getHost
          val containers = hostToContainers.getOrElseUpdate(host, new ArrayBuffer[Container]())

          containers += container
        }
        // Add all ignored containers to released list
        else releasedContainerList.add(container.getId())
      }

      // Find the appropriate containers to use
      // Slightly non trivial groupBy I guess ...
      val dataLocalContainers = new HashMap[String, ArrayBuffer[Container]]()
      val rackLocalContainers = new HashMap[String, ArrayBuffer[Container]]()
      val offRackContainers = new HashMap[String, ArrayBuffer[Container]]()

      for (candidateHost <- hostToContainers.keySet)
      {
        val maxExpectedHostCount = preferredHostToCount.getOrElse(candidateHost, 0)
        val requiredHostCount = maxExpectedHostCount - allocatedContainersOnHost(candidateHost)

        var remainingContainers = hostToContainers.get(candidateHost).getOrElse(null)
        assert(remainingContainers != null)

        if (requiredHostCount >= remainingContainers.size){
          // Since we got <= required containers, add all to dataLocalContainers
          dataLocalContainers.put(candidateHost, remainingContainers)
          // all consumed
          remainingContainers = null
        }
        else if (requiredHostCount > 0) {
          // container list has more containers than we need for data locality.
          // Split into two : data local container count of (remainingContainers.size - requiredHostCount) 
          // and rest as remainingContainer
          val (dataLocal, remaining) = remainingContainers.splitAt(remainingContainers.size - requiredHostCount)
          dataLocalContainers.put(candidateHost, dataLocal)
          // remainingContainers = remaining

          // yarn has nasty habit of allocating a tonne of containers on a host - discourage this :
          // add remaining to release list. If we have insufficient containers, next allocation cycle 
          // will reallocate (but wont treat it as data local)
          for (container <- remaining) releasedContainerList.add(container.getId())
          remainingContainers = null
        }

        // now rack local
        if (remainingContainers != null){
          val rack = YarnAllocationHandler.lookupRack(conf, candidateHost)

          if (rack != null){
            val maxExpectedRackCount = preferredRackToCount.getOrElse(rack, 0)
            val requiredRackCount = maxExpectedRackCount - allocatedContainersOnRack(rack) - 
              rackLocalContainers.get(rack).getOrElse(List()).size


            if (requiredRackCount >= remainingContainers.size){
              // Add all to dataLocalContainers
              dataLocalContainers.put(rack, remainingContainers)
              // all consumed
              remainingContainers = null
            }
            else if (requiredRackCount > 0) {
              // container list has more containers than we need for data locality.
              // Split into two : data local container count of (remainingContainers.size - requiredRackCount) 
              // and rest as remainingContainer
              val (rackLocal, remaining) = remainingContainers.splitAt(remainingContainers.size - requiredRackCount)
              val existingRackLocal = rackLocalContainers.getOrElseUpdate(rack, new ArrayBuffer[Container]())

              existingRackLocal ++= rackLocal
              remainingContainers = remaining
            }
          }
        }

        // If still not consumed, then it is off rack host - add to that list.
        if (remainingContainers != null){
          offRackContainers.put(candidateHost, remainingContainers)
        }
      }

      // Now that we have split the containers into various groups, go through them in order : 
      // first host local, then rack local and then off rack (everything else).
      // Note that the list we create below tries to ensure that not all containers end up within a host 
      // if there are sufficiently large number of hosts/containers.

      val allocatedContainers = new ArrayBuffer[Container](_allocatedContainers.size)
      allocatedContainers ++= ClusterScheduler.prioritizeContainers(dataLocalContainers)
      allocatedContainers ++= ClusterScheduler.prioritizeContainers(rackLocalContainers)
      allocatedContainers ++= ClusterScheduler.prioritizeContainers(offRackContainers)

      // Run each of the allocated containers
      for (container <- allocatedContainers) {
        val numWorkersRunningNow = numWorkersRunning.incrementAndGet()
        val workerHostname = container.getNodeId.getHost
        val containerId = container.getId

        assert (container.getResource.getMemory >= (workerMemory + YarnAllocationHandler.MEMORY_OVERHEAD))

        if (numWorkersRunningNow > maxWorkers) {
          logInfo("Ignoring container " + containerId + " at host " + workerHostname + 
            " .. we already have required number of containers")
          releasedContainerList.add(containerId)
          // reset counter back to old value.
          numWorkersRunning.decrementAndGet()
        }
        else {
          // deallocate + allocate can result in reusing id's wrongly - so use a different counter (workerIdCounter)
          val workerId = workerIdCounter.incrementAndGet().toString
          val driverUrl = "akka://spark@%s:%s/user/%s".format(
            System.getProperty("spark.driver.host"), System.getProperty("spark.driver.port"),
            StandaloneSchedulerBackend.ACTOR_NAME)

          logInfo("launching container on " + containerId + " host " + workerHostname)
          // just to be safe, simply remove it from pendingReleaseContainers. Should not be there, but ..
          pendingReleaseContainers.remove(containerId)

          val rack = YarnAllocationHandler.lookupRack(conf, workerHostname)
          allocatedHostToContainersMap.synchronized {
            val containerSet = allocatedHostToContainersMap.getOrElseUpdate(workerHostname, new HashSet[ContainerId]())

            containerSet += containerId
            allocatedContainerToHostMap.put(containerId, workerHostname)
            if (rack != null) allocatedRackCount.put(rack, allocatedRackCount.getOrElse(rack, 0) + 1)
          }

          new Thread(
            new WorkerRunnable(container, conf, driverUrl, workerId,
              workerHostname, workerMemory, workerCores)
          ).start()
        }
      }
      logDebug("After allocated " + allocatedContainers.size + " containers (orig : " + 
        _allocatedContainers.size + "), current count " + numWorkersRunning.get() +
        ", to-be-released " + releasedContainerList + ", pendingReleaseContainers : " + pendingReleaseContainers)
    }


    val completedContainers = amResp.getCompletedContainersStatuses()
    if (completedContainers.size > 0){
      logDebug("Completed " + completedContainers.size + " containers, current count " + numWorkersRunning.get() +
        ", to-be-released " + releasedContainerList + ", pendingReleaseContainers : " + pendingReleaseContainers)

      for (completedContainer <- completedContainers){
        val containerId = completedContainer.getContainerId

        // Was this released by us ? If yes, then simply remove from containerSet and move on.
        if (pendingReleaseContainers.containsKey(containerId)) {
          pendingReleaseContainers.remove(containerId)
        }
        else {
          // simply decrement count - next iteration of ReporterThread will take care of allocating !
          numWorkersRunning.decrementAndGet()
          logInfo("Container completed ? nodeId: " + containerId + ", state " + completedContainer.getState +
            " httpaddress: " + completedContainer.getDiagnostics)
        }

        allocatedHostToContainersMap.synchronized {
          if (allocatedContainerToHostMap.containsKey(containerId)) {
            val host = allocatedContainerToHostMap.get(containerId).getOrElse(null)
            assert (host != null)

            val containerSet = allocatedHostToContainersMap.get(host).getOrElse(null)
            assert (containerSet != null)

            containerSet -= containerId
            if (containerSet.isEmpty) allocatedHostToContainersMap.remove(host)
            else allocatedHostToContainersMap.update(host, containerSet)

            allocatedContainerToHostMap -= containerId

            // doing this within locked context, sigh ... move to outside ?
            val rack = YarnAllocationHandler.lookupRack(conf, host)
            if (rack != null) {
              val rackCount = allocatedRackCount.getOrElse(rack, 0) - 1
              if (rackCount > 0) allocatedRackCount.put(rack, rackCount)
              else allocatedRackCount.remove(rack)
            }
          }
        }
      }
      logDebug("After completed " + completedContainers.size + " containers, current count " + 
        numWorkersRunning.get() + ", to-be-released " + releasedContainerList + 
        ", pendingReleaseContainers : " + pendingReleaseContainers)
    }
  }

  def createRackResourceRequests(hostContainers: List[ResourceRequest]): List[ResourceRequest] = {
    // First generate modified racks and new set of hosts under it : then issue requests
    val rackToCounts = new HashMap[String, Int]()

    // Within this lock - used to read/write to the rack related maps too.
    for (container <- hostContainers) {
      val candidateHost = container.getHostName
      val candidateNumContainers = container.getNumContainers
      assert(YarnAllocationHandler.ANY_HOST != candidateHost)

      val rack = YarnAllocationHandler.lookupRack(conf, candidateHost)
      if (rack != null) {
        var count = rackToCounts.getOrElse(rack, 0)
        count += candidateNumContainers
        rackToCounts.put(rack, count)
      }
    }

    val requestedContainers: ArrayBuffer[ResourceRequest] = 
      new ArrayBuffer[ResourceRequest](rackToCounts.size)
    for ((rack, count) <- rackToCounts){
      requestedContainers += 
        createResourceRequest(AllocationType.RACK, rack, count, YarnAllocationHandler.PRIORITY)
    }

    requestedContainers.toList
  }

  def allocatedContainersOnHost(host: String): Int = {
    var retval = 0
    allocatedHostToContainersMap.synchronized {
      retval = allocatedHostToContainersMap.getOrElse(host, Set()).size
    }
    retval
  }

  def allocatedContainersOnRack(rack: String): Int = {
    var retval = 0
    allocatedHostToContainersMap.synchronized {
      retval = allocatedRackCount.getOrElse(rack, 0)
    }
    retval
  }

  private def allocateWorkerResources(numWorkers: Int): AllocateResponse = {

    var resourceRequests: List[ResourceRequest] = null

      // default.
    if (numWorkers <= 0 || preferredHostToCount.isEmpty) {
      logDebug("numWorkers: " + numWorkers + ", host preferences ? " + preferredHostToCount.isEmpty)
      resourceRequests = List(
        createResourceRequest(AllocationType.ANY, null, numWorkers, YarnAllocationHandler.PRIORITY))
    }
    else {
      // request for all hosts in preferred nodes and for numWorkers - 
      // candidates.size, request by default allocation policy.
      val hostContainerRequests: ArrayBuffer[ResourceRequest] = 
        new ArrayBuffer[ResourceRequest](preferredHostToCount.size)
      for ((candidateHost, candidateCount) <- preferredHostToCount) {
        val requiredCount = candidateCount - allocatedContainersOnHost(candidateHost)

        if (requiredCount > 0) {
          hostContainerRequests += 
            createResourceRequest(AllocationType.HOST, candidateHost, requiredCount, YarnAllocationHandler.PRIORITY)
        }
      }
      val rackContainerRequests: List[ResourceRequest] = createRackResourceRequests(hostContainerRequests.toList)

      val anyContainerRequests: ResourceRequest = 
        createResourceRequest(AllocationType.ANY, null, numWorkers, YarnAllocationHandler.PRIORITY)

      val containerRequests: ArrayBuffer[ResourceRequest] =
        new ArrayBuffer[ResourceRequest](hostContainerRequests.size() + rackContainerRequests.size() + 1)

      containerRequests ++= hostContainerRequests
      containerRequests ++= rackContainerRequests
      containerRequests += anyContainerRequests

      resourceRequests = containerRequests.toList
    }

    val req = Records.newRecord(classOf[AllocateRequest])
    req.setResponseId(lastResponseId.incrementAndGet)
    req.setApplicationAttemptId(appAttemptId)

    req.addAllAsks(resourceRequests)

    val releasedContainerList = createReleasedContainerList()
    req.addAllReleases(releasedContainerList)



    if (numWorkers > 0) {
      logInfo("Allocating " + numWorkers + " worker containers with " + (workerMemory + YarnAllocationHandler.MEMORY_OVERHEAD) + " of memory each.")
    }
    else {
      logDebug("Empty allocation req ..  release : " + releasedContainerList)
    }

    for (req <- resourceRequests) {
      logInfo("rsrcRequest ... host : " + req.getHostName + ", numContainers : " + req.getNumContainers +
        ", p = " + req.getPriority().getPriority + ", capability: "  + req.getCapability)
    }
    resourceManager.allocate(req)
  }


  private def createResourceRequest(requestType: AllocationType.AllocationType, 
                                    resource:String, numWorkers: Int, priority: Int): ResourceRequest = {

    // If hostname specified, we need atleast two requests - node local and rack local.
    // There must be a third request - which is ANY : that will be specially handled.
    requestType match {
      case AllocationType.HOST => {
        assert (YarnAllocationHandler.ANY_HOST != resource)

        val hostname = resource
        val nodeLocal = createResourceRequestImpl(hostname, numWorkers, priority)

        // add to host->rack mapping
        YarnAllocationHandler.populateRackInfo(conf, hostname)

        nodeLocal
      }

      case AllocationType.RACK => {
        val rack = resource
        createResourceRequestImpl(rack, numWorkers, priority)
      }

      case AllocationType.ANY => {
        createResourceRequestImpl(YarnAllocationHandler.ANY_HOST, numWorkers, priority)
      }

      case _ => throw new IllegalArgumentException("Unexpected/unsupported request type .. " + requestType)
    }
  }

  private def createResourceRequestImpl(hostname:String, numWorkers: Int, priority: Int): ResourceRequest = {

    val rsrcRequest = Records.newRecord(classOf[ResourceRequest])
    val memCapability = Records.newRecord(classOf[Resource])
    // There probably is some overhead here, let's reserve a bit more memory.
    memCapability.setMemory(workerMemory + YarnAllocationHandler.MEMORY_OVERHEAD)
    rsrcRequest.setCapability(memCapability)

    val pri = Records.newRecord(classOf[Priority])
    pri.setPriority(priority)
    rsrcRequest.setPriority(pri)

    rsrcRequest.setHostName(hostname)

    rsrcRequest.setNumContainers(java.lang.Math.max(numWorkers, 0))
    rsrcRequest
  }

  def createReleasedContainerList(): ArrayBuffer[ContainerId] = {

    val retval = new ArrayBuffer[ContainerId](1)
    // iterator on COW list ...
    for (container <- releasedContainerList.iterator()){
      retval += container
    }
    // remove from the original list.
    if (! retval.isEmpty) {
      releasedContainerList.removeAll(retval)
      for (v <- retval) pendingReleaseContainers.put(v, true)
      logInfo("Releasing " + retval.size + " containers. pendingReleaseContainers : " + 
        pendingReleaseContainers)
    }

    retval
  }
}

object YarnAllocationHandler {

  val ANY_HOST = "*"
  // all requests are issued with same priority : we do not (yet) have any distinction between 
  // request types (like map/reduce in hadoop for example)
  val PRIORITY = 1

  // Additional memory overhead - in mb
  val MEMORY_OVERHEAD = 384

  // host to rack map - saved from allocation requests
  // We are expecting this not to change.
  // Note that it is possible for this to change : and RM will indicate that to us via update 
  // response to allocate. But we are punting on handling that for now.
  private val hostToRack = new ConcurrentHashMap[String, String]()
  private val rackToHostSet = new ConcurrentHashMap[String, JSet[String]]()

  def newAllocator(conf: Configuration,
                   resourceManager: AMRMProtocol, appAttemptId: ApplicationAttemptId,
                   args: ApplicationMasterArguments,
                   map: collection.Map[String, collection.Set[SplitInfo]]): YarnAllocationHandler = {

    val (hostToCount, rackToCount) = generateNodeToWeight(conf, map)


    new YarnAllocationHandler(conf, resourceManager, appAttemptId, args.numWorkers, 
      args.workerMemory, args.workerCores, hostToCount, rackToCount)
  }

  def newAllocator(conf: Configuration,
                   resourceManager: AMRMProtocol, appAttemptId: ApplicationAttemptId,
                   maxWorkers: Int, workerMemory: Int, workerCores: Int,
                   map: collection.Map[String, collection.Set[SplitInfo]]): YarnAllocationHandler = {

    val (hostToCount, rackToCount) = generateNodeToWeight(conf, map)

    new YarnAllocationHandler(conf, resourceManager, appAttemptId, maxWorkers,
      workerMemory, workerCores, hostToCount, rackToCount)
  }

  // A simple method to copy the split info map.
  private def generateNodeToWeight(conf: Configuration, input: collection.Map[String, collection.Set[SplitInfo]]) :
  // host to count, rack to count
  (Map[String, Int], Map[String, Int]) = {

    if (input == null) return (Map[String, Int](), Map[String, Int]())

    val hostToCount = new HashMap[String, Int]
    val rackToCount = new HashMap[String, Int]

    for ((host, splits) <- input) {
      val hostCount = hostToCount.getOrElse(host, 0)
      hostToCount.put(host, hostCount + splits.size)

      val rack = lookupRack(conf, host)
      if (rack != null){
        val rackCount = rackToCount.getOrElse(host, 0)
        rackToCount.put(host, rackCount + splits.size)
      }
    }

    (hostToCount.toMap, rackToCount.toMap)
  }

  def lookupRack(conf: Configuration, host: String): String = {
    if (! hostToRack.contains(host)) populateRackInfo(conf, host)
    hostToRack.get(host)
  }

  def fetchCachedHostsForRack(rack: String): Option[Set[String]] = {
    val set = rackToHostSet.get(rack)
    if (set == null) return None

    // No better way to get a Set[String] from JSet ?
    val convertedSet: collection.mutable.Set[String] = set
    Some(convertedSet.toSet)
  }

  def populateRackInfo(conf: Configuration, hostname: String) {
    Utils.checkHost(hostname)

    if (!hostToRack.containsKey(hostname)) {
      // If there are repeated failures to resolve, all to an ignore list ?
      val rackInfo = RackResolver.resolve(conf, hostname)
      if (rackInfo != null && rackInfo.getNetworkLocation != null) {
        val rack = rackInfo.getNetworkLocation
        hostToRack.put(hostname, rack)
        if (! rackToHostSet.containsKey(rack)) {
          rackToHostSet.putIfAbsent(rack, Collections.newSetFromMap(new ConcurrentHashMap[String, JBoolean]()))
        }
        rackToHostSet.get(rack).add(hostname)

        // Since RackResolver caches, we are disabling this for now ...
      } /* else {
        // right ? Else we will keep calling rack resolver in case we cant resolve rack info ...
        hostToRack.put(hostname, null)
      } */
    }
  }
}
