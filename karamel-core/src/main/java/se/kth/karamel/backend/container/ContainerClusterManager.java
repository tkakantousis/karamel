package se.kth.karamel.backend.container;

import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.DockerException;
import com.spotify.docker.client.messages.AuthConfig;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;
import com.spotify.docker.client.messages.NetworkConfig;
import com.spotify.docker.client.messages.NetworkCreation;
import com.spotify.docker.client.messages.ContainerCreation;
import org.apache.log4j.Logger;
import se.kth.karamel.backend.converter.UserClusterDataExtractor;
import se.kth.karamel.backend.machines.TaskSubmitter;
import se.kth.karamel.backend.running.model.ClusterRuntime;
import se.kth.karamel.backend.running.model.GroupRuntime;
import se.kth.karamel.backend.running.model.NodeRunTime;
import se.kth.karamel.client.api.CookbookCache;
import se.kth.karamel.common.clusterdef.json.JsonCluster;
import se.kth.karamel.common.clusterdef.json.JsonCookbook;
import se.kth.karamel.common.clusterdef.json.JsonGroup;
import se.kth.karamel.common.clusterdef.json.JsonRecipe;
import se.kth.karamel.common.cookbookmeta.KaramelizedCookbook;
import se.kth.karamel.common.cookbookmeta.MetadataRb;
import se.kth.karamel.common.exception.KaramelException;
import se.kth.karamel.common.stats.ClusterStats;
import se.kth.karamel.common.util.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class ContainerClusterManager {

  private static final Logger logger = Logger.getLogger(ContainerClusterManager.class);

  private HashMap<String, List<NodeRunTime>> containerHostMap = new HashMap<>();

  /**
   * Mapping for Containers and for Groups
   */
  private HashMap<String, ArrayList<NodeRunTime>> containerGroupMap = new HashMap<>();

  /**
   * List of Docker Clients
   */
  private HashMap<String, DockerClient> dockerClientMap = new HashMap<>();

  /**
   * List of Docker Host Machines
   */
  private List<NodeRunTime> hostMachineRuntimes = new ArrayList<>();

  /**
   * This maintains the list of Group runtimes dedicated to containers. In this list Container Host Group
   * is filtered out
   */
  List<GroupRuntime> containerGroupRuntimes = new ArrayList<>();
  private ClusterRuntime runtime;
  private JsonCluster cluster;
  private ClusterStats clusterStats;
  private TaskSubmitter taskSubmitter;
  private int numOfContainers = 0;

  public ContainerClusterManager(ClusterRuntime runtime, JsonCluster cluster, ClusterStats clusterstats,
                                 TaskSubmitter taskSubmitter) {
    this.runtime = runtime;
    this.cluster = cluster;
    this.taskSubmitter = taskSubmitter;
    this.clusterStats = clusterstats;
    init();
  }

  public HashMap<String, ArrayList<NodeRunTime>> StartContainers() throws KaramelException, InterruptedException,
    DockerException {
    for (JsonGroup jsonGroup : cluster.getGroups()) {
      if (!Settings.CONTAINER_HOST_GROUP.equals(jsonGroup.getName())) {
        numOfContainers += jsonGroup.getSize();
        containerGroupMap.put(jsonGroup.getName(), new ArrayList<NodeRunTime>());
      }
    }

    List<NodeRunTime> machines = new ArrayList<>();

    int containerOffset = 0;

    for (String groupName : containerGroupMap.keySet()) {
      JsonGroup group = UserClusterDataExtractor.findGroup(cluster, groupName);

      for (int i = 0; i < group.getSize(); i++) {

        int position = containerOffset % hostMachineRuntimes.size();
        NodeRunTime hostMachine = hostMachineRuntimes.get(position);

        int sshPort = 11000 + containerOffset;
        String publicIp = hostMachine.getPublicIp();

        DockerClient client = dockerClientMap.get(publicIp);

        client.pull("shelan/karamel-node:v2.0.0", AuthConfig.builder().build());

        final Map<String, List<PortBinding>> portBindings = new HashMap<>();
        List<PortBinding> hostPorts = new ArrayList<>();
        hostPorts.add(PortBinding.of("0.0.0.0", sshPort));
        portBindings.put("22", hostPorts);

        //TODO: exposing all the ports found in links in every container, We might not want to do that. and only need
        // to expose relevant port for containers.
        String[] clusterLinks = UserClusterDataExtractor.clusterLinks(cluster, runtime).split("\n");
        List<String> ports = new ArrayList();

        for (int j = 0; j < clusterLinks.length; j++) {
          ports.add(clusterLinks[j].split("//")[1].split("/")[0].split(":")[1]);
        }

        for (String port : ports) {
          List<PortBinding> randomHostPorts = new ArrayList<PortBinding>();
          randomHostPorts.add(PortBinding.randomPort("0.0.0.0"));
          portBindings.put(port, randomHostPorts);
        }

        //adding SSH port to expose
        ports.add("22");

        HostConfig hostConfig = HostConfig.builder()
          .networkMode("karamel")
          .portBindings(portBindings)
          .build();

        String[] exposedPorts = new String[ports.size()];
        exposedPorts = ports.toArray(exposedPorts);

        ContainerConfig containerConfig = ContainerConfig.builder()
          .image("shelan/karamel-node:v2.0.0")
          .hostConfig(hostConfig)
          .exposedPorts(exposedPorts)
          .hostname("node" + containerOffset)
          .build();


        final ContainerCreation creation = client.createContainer(containerConfig, "node" + containerOffset);
        final String id = creation.id();
        client.startContainer(id);
        String containerIp = client.inspectContainer(id).networkSettings().networks().get("karamel").ipAddress();

        NodeRunTime containerRuntime = new NodeRunTime(hostMachine.getGroup());
        containerRuntime.setNodeType(NodeRunTime.NodeType.CONTAINER);
        containerRuntime.setMachineType(NodeRunTime.NodeType.CONTAINER.name());
        containerRuntime.setName(id);
        containerRuntime.setVmId(id);
        containerRuntime.setPrivateIp(containerIp);
        containerRuntime.setPublicIp(publicIp);
        containerRuntime.setSshPort(sshPort);
        containerRuntime.setSshUser("vagrant");
        machines.add(containerRuntime);
        containerGroupMap.get(groupName).add(containerRuntime);

        containerOffset++;
      }
    }
    return containerGroupMap;
  }

  private void init() {
    for (GroupRuntime groupRuntime : runtime.getGroups()) {
      if (Settings.CONTAINER_HOST_GROUP.equals(groupRuntime.getName())) {
        // this is the host group lets add all the machines to the host machines list
        this.hostMachineRuntimes.addAll(groupRuntime.getMachines());
      }
    }

    for (NodeRunTime nodeRunTime : hostMachineRuntimes) {
      String publicIp = nodeRunTime.getPublicIp();
      containerHostMap.put(publicIp, new ArrayList<NodeRunTime>());
      DockerClient docker = new DefaultDockerClient("http://" + nodeRunTime.getPublicIp() + ":2375");
      AuthConfig authConfig = AuthConfig.builder().serverAddress("https://index.docker.io/v1/").build();
      try {
        docker.auth(authConfig);
      } catch (DockerException e) {
        logger.error("Error while initializing docker clients", e);
      } catch (InterruptedException e) {
        logger.error("Interrupted while initializing docker clients", e);
      }
      dockerClientMap.put(publicIp, docker);
    }
  }

  public int getNOfContainers() {
    return numOfContainers;
  }

  //TODO: this is the core logic to parse existing port should be removed if not used in future.
  public void extractPorts() throws KaramelException {
    for (JsonGroup jsonGroup : cluster.getGroups()) {
      for (JsonCookbook jsonCookbook : jsonGroup.getCookbooks()) {
        for (JsonRecipe jsonRecipe : jsonCookbook.getRecipes()) {
          String cbid = jsonCookbook.getId();
          KaramelizedCookbook cb = CookbookCache.get(cbid);
          MetadataRb metadataRb = cb.getMetadataRb();
        }
      }
    }
  }

  public void setupNetworking(String kvStorePublicIP, String kvStorePrivateIP) throws DockerException,
    InterruptedException {
    DockerClient client = dockerClientMap.get(kvStorePublicIP);
    client.pull("progrium/consul:latest", AuthConfig.builder().build());

    final Map<String, List<PortBinding>> portBindings = new HashMap<String, List<PortBinding>>();
    List<PortBinding> hostPorts = new ArrayList<PortBinding>();
    hostPorts.add(PortBinding.of("0.0.0.0", 8500));
    portBindings.put("8500", hostPorts);
    HostConfig hostConfig = HostConfig.builder().portBindings(portBindings).build();
    ContainerConfig containerConfig = ContainerConfig.builder().image("progrium/consul:latest")
      .hostConfig(hostConfig)
      .cmd("-server", "-bootstrap")
      .exposedPorts("8500")
      .build();

    final ContainerCreation creation = client.createContainer(containerConfig);
    final String id = creation.id();

    client.startContainer(id);

    //Ipam ipam = Ipam.builder().config("10.0.4.0/24","10.0.4.0/24","10.0.4.255").build();
    NetworkConfig networkConfig = NetworkConfig.builder().driver("overlay").name("karamel").build();
    NetworkCreation networkCreation = null;

    while (networkCreation == null || !(networkCreation.id().length() > 0)) {
      try {
        networkCreation = client.createNetwork(networkConfig);
      } catch (Exception e){
        Thread.sleep(1000);
      }
    }

  }

}