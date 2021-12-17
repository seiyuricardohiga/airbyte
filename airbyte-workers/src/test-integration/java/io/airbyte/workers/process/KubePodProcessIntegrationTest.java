/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.workers.WorkerException;
import io.airbyte.workers.WorkerUtils;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// requires kube running locally to run. If using Minikube it requires MINIKUBE=true
// Must have a timeout on this class because it tests child processes that may misbehave; otherwise
// this can hang forever during failures.
@Timeout(value = 5,
         unit = TimeUnit.MINUTES)
public class KubePodProcessIntegrationTest {
  private static final int RANDOM_FILE_LINE_LENGTH = 100;

  private static final boolean IS_MINIKUBE = Boolean.parseBoolean(Optional.ofNullable(System.getenv("IS_MINIKUBE")).orElse("false"));
  private static List<Integer> openPorts;
  private static int heartbeatPort;
  private static String heartbeatUrl;
  private static ApiClient officialClient;
  private static KubernetesClient fabricClient;
  private static KubeProcessFactory processFactory;

  private WorkerHeartbeatServer server;

  @BeforeAll
  public static void init() throws Exception {
    openPorts = new ArrayList<>(getOpenPorts(5));

    heartbeatPort = openPorts.get(0);
    heartbeatUrl = getHost() + ":" + heartbeatPort;

    officialClient = Config.defaultClient();
    fabricClient = new DefaultKubernetesClient();

    processFactory = new KubeProcessFactory("default", officialClient, fabricClient, heartbeatUrl, getHost(),
        new HashSet<>(openPorts.subList(1, openPorts.size() - 1)));
  }

  @BeforeEach
  public void setup() throws Exception {
    server = new WorkerHeartbeatServer(heartbeatPort);
    server.startBackground();
  }

  @AfterEach
  public void teardown() throws Exception {
    server.stop();
  }

  @Test
  public void testSuccessfulSpawning() throws Exception {
    // start a finite process
    final var availablePortsBefore = KubePortManagerSingleton.getInstance().getNumAvailablePorts();
    final Process process = getProcess("echo hi; sleep 1; echo hi2");
    process.waitFor();

    // the pod should be dead and in a good state
    assertFalse(process.isAlive());
    assertEquals(availablePortsBefore, KubePortManagerSingleton.getInstance().getNumAvailablePorts());
    assertEquals(0, process.exitValue());
  }

  @Test
  public void testPortsReintroducedIntoPoolOnlyOnce() throws Exception {
    final var availablePortsBefore = KubePortManagerSingleton.getInstance().getNumAvailablePorts();

    // run a finite process
    final Process process = getProcess("echo hi; sleep 1; echo hi2");
    process.waitFor();

    // the pod should be dead and in a good state
    assertFalse(process.isAlive());

    // run a background process to continuously consume available ports
    final var portsTaken = new ArrayList<Integer>();
    final var executor = Executors.newSingleThreadExecutor();

    executor.submit(() -> {
      try {
        while (true) {
          portsTaken.add(KubePortManagerSingleton.getInstance().take());
        }
      } catch (final InterruptedException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    });

    // repeatedly call exitValue (and therefore the close method)
    for (int i = 0; i < 100; i++) {
      // if exitValue no longer calls close in the future this test will fail and need to be updated.
      process.exitValue();
    }

    // stop taking from available ports
    executor.shutdownNow();

    // prior to fixing this race condition, the close method would offer ports every time it was called.
    // without the race condition, we should have only been able to pull each of the originally
    // available ports once
    assertEquals(availablePortsBefore, portsTaken.size());

    // release ports for next tests
    portsTaken.forEach(KubePortManagerSingleton.getInstance()::offer);
  }

  @Test
  public void testSuccessfulSpawningWithQuotes() throws Exception {
    // start a finite process
    final var availablePortsBefore = KubePortManagerSingleton.getInstance().getNumAvailablePorts();
    final Process process = getProcess("echo \"h\\\"i\"; sleep 1; echo hi2");
    final var output = new String(process.getInputStream().readAllBytes());
    assertEquals("h\"i\nhi2\n", output);
    process.waitFor();

    // the pod should be dead and in a good state
    assertFalse(process.isAlive());
    assertEquals(availablePortsBefore, KubePortManagerSingleton.getInstance().getNumAvailablePorts());
    assertEquals(0, process.exitValue());
  }

  @Test
  public void testPipeInEntrypoint() throws Exception {
    // start a process that has a pipe in the entrypoint
    final var availablePortsBefore = KubePortManagerSingleton.getInstance().getNumAvailablePorts();
    final Process process = getProcess("echo hi | cat");
    process.waitFor();

    // the pod should be dead and in a good state
    assertFalse(process.isAlive());
    assertEquals(availablePortsBefore, KubePortManagerSingleton.getInstance().getNumAvailablePorts());
    assertEquals(0, process.exitValue());
  }

  @Test
  public void testExitCodeRetrieval() throws Exception {
    // start a process that requests
    final var availablePortsBefore = KubePortManagerSingleton.getInstance().getNumAvailablePorts();
    final Process process = getProcess("exit 10");
    process.waitFor();

    // the pod should be dead with the correct error code
    assertFalse(process.isAlive());
    assertEquals(availablePortsBefore, KubePortManagerSingleton.getInstance().getNumAvailablePorts());
    assertEquals(10, process.exitValue());
  }

  @Test
  public void testMissingEntrypoint() throws WorkerException, InterruptedException {
    // start a process with an entrypoint that doesn't exist
    final var availablePortsBefore = KubePortManagerSingleton.getInstance().getNumAvailablePorts();
    final Process process = getProcess(null);
    process.waitFor();

    // the pod should be dead and in an error state
    assertFalse(process.isAlive());
    assertEquals(availablePortsBefore, KubePortManagerSingleton.getInstance().getNumAvailablePorts());
    assertEquals(127, process.exitValue());
  }

  @Test
  public void testKillingWithoutHeartbeat() throws Exception {
    // start an infinite process
    final var availablePortsBefore = KubePortManagerSingleton.getInstance().getNumAvailablePorts();
    final Process process = getProcess("while true; do echo hi; sleep 1; done");

    // kill the heartbeat server
    server.stop();

    // waiting for process
    process.waitFor();

    // the pod should be dead and in an error state
    assertFalse(process.isAlive());
    assertEquals(availablePortsBefore, KubePortManagerSingleton.getInstance().getNumAvailablePorts());
    assertNotEquals(0, process.exitValue());
  }

  @Test
  public void testCopyLargeFiles() throws Exception {
    final int numFiles = 3;
    final int numLinesPerFile = 10000;

    final Map<String, String> files = Maps.newHashMapWithExpectedSize(numFiles);
    for (int i = 0; i < numFiles; i++) {
      files.put("file" + i, getRandomFile(numLinesPerFile));
    }

    final long minimumConfigDirSize = (long)numFiles * numLinesPerFile * RANDOM_FILE_LINE_LENGTH;

    final Process process = getProcess(
        "CONFIG_DIR_SIZE=$(du -sb /config | awk '{print $1;}'); echo $CONFIG_DIR_SIZE >> /pipes/stdout; exit 0;",
        files);

    final String processOutput = new String(process.getInputStream().readAllBytes());
    final long actualConfigDirSize = Long.parseLong(processOutput.trim());
    process.waitFor();

    assertTrue(
        actualConfigDirSize > minimumConfigDirSize,
        "(actual config directory size) " + actualConfigDirSize + " > " + minimumConfigDirSize + " (minimum expected config directory size)");
  }

  private static String getRandomFile(final int lines) {
    final var sb = new StringBuilder();
    for (int i = 0; i < lines; i++) {
      sb.append(RandomStringUtils.randomAlphabetic(RANDOM_FILE_LINE_LENGTH));
      sb.append("\n");
    }
    return sb.toString();
  }

  private Process getProcess(final String entrypoint) throws WorkerException {
    // these files aren't used for anything, it's just to check for exceptions when uploading
    final var files = ImmutableMap.of(
        "file0", "fixed str",
        "file1", getRandomFile(1),
        "file2", getRandomFile(100),
        "file3", getRandomFile(1000));

    return getProcess(entrypoint, files);
  }

  private Process getProcess(final String entrypoint, final Map<String, String> files) throws WorkerException {
    return processFactory.create(
        "some-id",
        0,
        Path.of("/tmp/job-root"),
        "busybox:latest",
        false,
        files,
        entrypoint, WorkerUtils.DEFAULT_RESOURCE_REQUIREMENTS, Map.of());
  }

  private static Set<Integer> getOpenPorts(final int count) {
    final Set<ServerSocket> servers = new HashSet<>();
    final Set<Integer> ports = new HashSet<>();

    try {
      for (int i = 0; i < count; i++) {
        final var server = new ServerSocket(0);
        servers.add(server);
        ports.add(server.getLocalPort());
      }
    } catch (final IOException e) {
      throw new RuntimeException(e);
    } finally {
      for (final ServerSocket server : servers) {
        Exceptions.swallow(server::close);
      }
    }

    return ports;
  }

  private static String getHost() {
    try {
      return (IS_MINIKUBE ? Inet4Address.getLocalHost().getHostAddress() : "host.docker.internal");
    } catch (final UnknownHostException e) {
      throw new RuntimeException(e);
    }
  }

}
