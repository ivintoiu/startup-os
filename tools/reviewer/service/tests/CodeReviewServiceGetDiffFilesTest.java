/*
 * Copyright 2018 The StartupOS Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.startupos.tools.reviewer.service.tests;

import com.google.startupos.common.CommonModule;
import com.google.startupos.common.FileUtils;
import com.google.startupos.common.TextDifferencer;
import com.google.startupos.common.firestore.FirestoreClient;
import com.google.startupos.common.firestore.FirestoreClientFactory;
import com.google.startupos.common.flags.Flags;
import com.google.startupos.common.repo.GitRepo;
import com.google.startupos.common.repo.GitRepoFactory;
import com.google.startupos.tools.aa.AaModule;
import com.google.startupos.tools.aa.commands.DiffCommand;
import com.google.startupos.tools.aa.commands.InitCommand;
import com.google.startupos.tools.aa.commands.WorkspaceCommand;
import com.google.startupos.tools.localserver.service.AuthService;
import com.google.startupos.tools.reviewer.service.CodeReviewService;
import com.google.startupos.tools.reviewer.service.CodeReviewServiceGrpc;
import com.google.startupos.tools.reviewer.service.Protos;
import dagger.Component;
import dagger.Provides;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class CodeReviewServiceGetDiffFilesTest {
  private static final String TEST_FILE = "test_file.txt";
  private static final String TEST_FILE_CONTENTS = "Some test file contents\n";
  private static final String FILE_IN_HEAD = "im_in_head.txt";
  private static final String TEST_WORKSPACE = "ws1";
  private static final String COMMIT_MESSAGE = "Some commit message";
  private static final String REPO_ID = "startup-os";

  private GitRepoFactory gitRepoFactory;
  private String aaBaseFolder;
  private String testFileCommitId;
  private String fileInHeadCommitId;
  private GitRepo repo;
  private FileUtils fileUtils;
  private CodeReviewServiceGetDiffFilesTest.TestComponent component;
  private Server server;
  private ManagedChannel channel;
  private CodeReviewServiceGrpc.CodeReviewServiceBlockingStub blockingStub;
  private CodeReviewService codeReviewService;

  private FirestoreClientFactory firestoreClientFactory = mock(FirestoreClientFactory.class);
  private FirestoreClient firestoreClient = mock(FirestoreClient.class);

  @Before
  public void setup() throws IOException {
    Flags.parse(
        new String[0], AuthService.class.getPackage(), CodeReviewService.class.getPackage());
    String testFolder = Files.createTempDirectory("temp").toAbsolutePath().toString();
    String initialRepoFolder = joinPaths(testFolder, "initial_repo");
    aaBaseFolder = joinPaths(testFolder, "base_folder");

    component =
        DaggerCodeReviewServiceGetDiffFilesTest_TestComponent.builder()
            .aaModule(
                new AaModule() {
                  @Provides
                  @Singleton
                  @Override
                  @Named("Base path")
                  public String provideBasePath(FileUtils fileUtils) {
                    return aaBaseFolder;
                  }
                })
            .build();
    gitRepoFactory = component.getGitRepoFactory();
    fileUtils = component.getFileUtils();

    String projectId = component.getAuthService().getProjectId();
    String token = component.getAuthService().getToken();
    when(firestoreClientFactory.create(projectId, token)).thenReturn(firestoreClient);
    firestoreClientFactory.create(projectId, token);

    codeReviewService =
        new CodeReviewService(
            component.getAuthService(),
            fileUtils,
            aaBaseFolder,
            gitRepoFactory,
            component.getTextDifferencer(),
            firestoreClientFactory);

    createInitialRepo(initialRepoFolder);
    initAaBase(initialRepoFolder, aaBaseFolder);
    createAaWorkspace(TEST_WORKSPACE);
    createBlockingStub();
    writeFile(TEST_FILE_CONTENTS);
    testFileCommitId = repo.commit(repo.getUncommittedFiles(), COMMIT_MESSAGE).getId();
  }

  @After
  public void after() throws InterruptedException {
    server.shutdownNow();
    server.awaitTermination();
    channel.shutdownNow();
    channel.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Singleton
  @Component(modules = {CommonModule.class, AaModule.class})
  interface TestComponent {
    AuthService getAuthService();

    GitRepoFactory getGitRepoFactory();

    InitCommand getInitCommand();

    WorkspaceCommand getWorkspaceCommand();

    FileUtils getFileUtils();

    TextDifferencer getTextDifferencer();

    DiffCommand getDiffCommand();
  }

  private void createInitialRepo(String initialRepoFolder) {
    fileUtils.mkdirs(initialRepoFolder);
    GitRepo repo = gitRepoFactory.create(initialRepoFolder);
    repo.init();
    repo.setFakeUsersData();
    fileUtils.writeStringUnchecked(
        TEST_FILE_CONTENTS, fileUtils.joinPaths(initialRepoFolder, FILE_IN_HEAD));
    fileInHeadCommitId = repo.commit(repo.getUncommittedFiles(), "Initial commit").getId();
  }

  private void initAaBase(String initialRepoFolder, String aaBaseFolder) {
    InitCommand initCommand = component.getInitCommand();
    InitCommand.basePath.resetValueForTesting();
    InitCommand.startuposRepo.resetValueForTesting();
    String[] args = {
      "--startupos_repo", initialRepoFolder,
      "--base_path", aaBaseFolder,
    };
    initCommand.run(args);
  }

  private void createAaWorkspace(String name) {
    WorkspaceCommand workspaceCommand = component.getWorkspaceCommand();
    String[] args = {"workspace", "-f", name};
    workspaceCommand.run(args);
    String repoPath = fileUtils.joinPaths(getWorkspaceFolder(TEST_WORKSPACE), "startup-os");
    repo = gitRepoFactory.create(repoPath);
    repo.setFakeUsersData();
  }

  private void createBlockingStub() throws IOException {
    // Generate a unique in-process server name.
    String serverName = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(codeReviewService)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    blockingStub = CodeReviewServiceGrpc.newBlockingStub(channel);
  }

  public String joinPaths(String first, String... more) {
    return FileSystems.getDefault().getPath(first, more).toAbsolutePath().toString();
  }

  private String getWorkspaceFolder(String workspace) {
    return joinPaths(aaBaseFolder, "ws", workspace);
  }

  private void writeFile(String contents) {
    writeFile(TEST_FILE, contents);
  }

  private void writeFile(String filename, String contents) {
    fileUtils.writeStringUnchecked(
        contents, fileUtils.joinPaths(getWorkspaceFolder(TEST_WORKSPACE), "startup-os", filename));
  }

  private Protos.DiffFilesResponse getExpectedResponse(String commitId) {
    return Protos.DiffFilesResponse.newBuilder()
        .addAllBranchInfo(
            Collections.singleton(
                com.google.startupos.common.repo.Protos.BranchInfo.newBuilder()
                    .setDiffId(2)
                    .setRepoId(REPO_ID)
                    .addCommit(
                        com.google.startupos.common.repo.Protos.Commit.newBuilder().setId(commitId))
                    .build()))
        .build();
  }

  private Protos.DiffFilesResponse getResponse(String workspace, long diffNumber) {
    Protos.DiffFilesRequest request =
        Protos.DiffFilesRequest.newBuilder().setWorkspace(workspace).setDiffId(diffNumber).build();

    return blockingStub.getDiffFiles(request);
  }

  @Test
  public void testFirestoreClientMock() {
    when(firestoreClient.sum(2, 2)).thenReturn(5L);
    assertEquals(5, codeReviewService.sum(2, 2));
  }

  @Test
  public void testFirst() {
    // switch workspace
//    WorkspaceCommand workspaceCommand = component.getWorkspaceCommand();
//    String[] args = {"workspace", TEST_WORKSPACE};
//    workspaceCommand.run(args);

    when(firestoreClient.getDocument(
            "reviewer/data/diff/2", Protos.DiffFilesResponse.newBuilder()))
        .thenReturn(getExpectedResponse(testFileCommitId));
    when(firestoreClient.getDocument(
        "/reviewer/data/last_diff_id", Protos.DiffNumberResponse.newBuilder()))
        .thenReturn(Protos.DiffNumberResponse.newBuilder().setLastDiffId(1).build());

    component.getDiffCommand().run(new String[] {"diff"});
    assertEquals(getExpectedResponse(testFileCommitId), getResponse(TEST_WORKSPACE, 2));
  }
}

