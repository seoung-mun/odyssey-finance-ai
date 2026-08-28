package com.dacon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

class LayerArchitectureTest {
  @Test
  void legacyFeaturePackagesAreGone() {
    Path sourceRoot = Path.of("src/main/java/com/dacon/core");

    assertThat(sourceRoot.resolve("account")).doesNotExist();
    assertThat(sourceRoot.resolve("financial")).doesNotExist();
    assertThat(sourceRoot.resolve("planning")).doesNotExist();
    assertThat(Files.isDirectory(sourceRoot.resolve("user"))).isTrue();
    assertThat(Files.isDirectory(sourceRoot.resolve("goal"))).isTrue();
    assertThat(Files.isDirectory(sourceRoot.resolve("plan"))).isTrue();
  }

  @Test
  void sourceDoesNotUseForbiddenPersistenceShortcuts() throws Exception {
    List<Path> sources = javaSources();

    assertThat(sources)
        .allSatisfy(
            source -> {
              String code = Files.readString(source);
              assertThat(code).as(source.toString()).doesNotContain("JdbcTemplate");
              assertThat(code).as(source.toString()).doesNotContainPattern("\\bvar\\s+");
              assertThat(code)
                  .as(source.toString())
                  .doesNotContainPattern("\\b(class|interface|record)\\s+\\w*Store\\b");
              assertThat(code).as(source.toString()).doesNotContainPattern("\\bMap\\s+\\w+");
            });
  }

  @Test
  void controllersDoNotImportRepositories() throws Exception {
    assertThat(
            javaSources().stream()
                .filter(path -> path.getFileName().toString().endsWith("Controller.java")))
        .allSatisfy(
            controller ->
                assertThat(Files.readString(controller))
                    .as(controller.toString())
                    .doesNotContainPattern("import\\s+.*Repository;"));
  }

  @Test
  void controllersDependOnServiceInterfaces() throws Exception {
    assertConstructorUsesInterface(
        "com.dacon.core.auth.AuthController", "com.dacon.core.auth.AuthService");
    assertConstructorUsesInterface(
        "com.dacon.core.goal.GoalController", "com.dacon.core.goal.GoalService");
    assertConstructorUsesInterface(
        "com.dacon.core.user.UserController", "com.dacon.core.user.UserService");
    assertConstructorUsesInterface(
        "com.dacon.core.plan.PlanningController", "com.dacon.core.plan.PlanningService");
    assertConstructorUsesInterface(
        "com.dacon.core.transaction.TransactionController",
        "com.dacon.core.transaction.TransactionService");
  }

  @Test
  void persistenceInterfacesUseSpringDataJpa() throws Exception {
    for (String name :
        List.of(
            "com.dacon.core.user.repository.UserProfileRepository",
            "com.dacon.core.goal.FinancialGoalRepository",
            "com.dacon.core.plan.PlanVersionRepository",
            "com.dacon.core.transaction.TransactionRepository")) {
      assertThat(JpaRepository.class.isAssignableFrom(Class.forName(name))).as(name).isTrue();
    }
  }

  @Test
  void commandAndQueryTransactionsAreDeclaredOnServiceImplementation() throws Exception {
    Class<?> implementation = Class.forName("com.dacon.core.transaction.TransactionServiceImpl");
    Method command = implementation.getDeclaredMethod("importAll", int.class, List.class);
    Transactional commandTransaction = command.getAnnotation(Transactional.class);

    assertThat(commandTransaction).isNotNull();
    assertThat(commandTransaction.readOnly()).isFalse();
  }

  @Test
  void externalDependenciesArePorts() throws Exception {
    assertThat(Class.forName("com.dacon.core.analysis.AnalysisServicePort").isInterface()).isTrue();
    assertThat(Class.forName("com.dacon.core.explanation.ExplanationQueuePort").isInterface())
        .isTrue();
  }

  private void assertConstructorUsesInterface(String ownerName, String dependencyName)
      throws Exception {
    Class<?> owner = Class.forName(ownerName);
    Class<?> dependency = Class.forName(dependencyName);
    assertThat(dependency.isInterface()).as(dependencyName).isTrue();
    assertThat(
            List.of(owner.getDeclaredConstructors()).stream()
                .map(Constructor::getParameterTypes)
                .flatMap(types -> List.of(types).stream())
                .anyMatch(dependency::equals))
        .as(ownerName)
        .isTrue();
  }

  private List<Path> javaSources() throws Exception {
    List<Path> sources = new ArrayList<>();
    try (java.util.stream.Stream<Path> paths =
        Files.walk(Path.of("src/main/java/com/dacon/core"))) {
      paths.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
    }
    return sources;
  }
}
