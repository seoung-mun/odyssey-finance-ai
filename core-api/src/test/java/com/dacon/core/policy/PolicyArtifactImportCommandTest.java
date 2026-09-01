package com.dacon.core.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

class PolicyArtifactImportCommandTest {
  @TempDir Path directory;

  @Test
  void rejectsDirectorySymlinkAndOversizedFileBeforeReading() throws Exception {
    DefaultApplicationArguments arguments = new DefaultApplicationArguments();
    assertThatThrownBy(
            () -> new PolicyArtifactImportCommand(null, directory.toString()).run(arguments))
        .isInstanceOf(IllegalArgumentException.class);

    Path target = Files.writeString(directory.resolve("target.json"), "{}");
    Path symlink = Files.createSymbolicLink(directory.resolve("link.json"), target);
    assertThatThrownBy(
            () -> new PolicyArtifactImportCommand(null, symlink.toString()).run(arguments))
        .isInstanceOf(IllegalArgumentException.class);

    Path oversized = directory.resolve("oversized.json");
    try (var channel =
        java.nio.channels.FileChannel.open(
            oversized,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE)) {
      channel.position(PolicyArtifactImportCommand.MAX_ARTIFACT_BYTES);
      channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
    }
    assertThatThrownBy(
            () -> new PolicyArtifactImportCommand(null, oversized.toString()).run(arguments))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
