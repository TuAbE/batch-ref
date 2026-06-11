# BatchRef Spring Boot Starter

BatchRef 用于把循环里的单个查询描述收集起来，在 `@BatchScope` 自动 flush 时按查询方法合并成批量查询，再把 `setOut`、`whenPresent`、`whenAbsent`、`whenValue` 等步骤回放到业务对象上。

完整设计稿保留在 [BatchRef.md](BatchRef.md)，业务使用方式以其中示例为准，本实现不改变文档里的调用方式。

## 环境

- Java 17
- Spring Boot 3.5.x

## 引入

```xml
<dependency>
    <groupId>io.github.batchref</groupId>
    <artifactId>batch-ref-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Spring Boot 自动装配

starter 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动装配 `BatchScopeAspect` 和 `BatchQueryMethodAspect`。

可配置项：

```yaml
batch-ref:
  enabled: true
  auto-flush: true
  flush-on-exception: false
  aspect-order: 2147483547
```

`auto-flush=true` 时，`@BatchScope` 方法正常返回前会自动 flush。业务代码只登记填充步骤，不需要手动调用 `BatchRefs.flush()`。

## 业务代码

```java
@BatchScope
public List<ProjectVO> getProjectList(ProjectListParam param) {
    List<ProjectVO> projectList = queryProjectList(param);

    for (ProjectVO project : projectList) {
        BatchRef<GeneralContractingProjectGroupRelation> relationRef =
                BatchRef.wrap(
                        relationQueries::getActiveRelationByWorkerProjectId,
                        project.getProjectId()
                );

        relationRef.whenPresent(relation -> project.setRelatedToGc(relation.getId() != null));
        relationRef.whenAbsent(() -> project.setRelatedToGc(false));
        relationRef.setOut(
                project::setGcProjectId,
                GeneralContractingProjectGroupRelation::getGcProjectId
        );
        relationRef.setOut(project::setNeedGcApproval)
                .fromMapped(
                        GeneralContractingProjectGroupRelation::getNeedApproval,
                        Boolean.TRUE::equals
                );
    }

    return projectList;
}
```

没有 `@BatchScope` 时，`BatchRef.wrap(...)` 会直接执行被 `@BatchQueryMethod` 标注的方法，并在后续登记步骤时立即执行对应动作。

## 方法绑定示例

`@BatchQueryMethod.batchMethod` 填当前类里的批量方法名。

```java
@BatchQueryMethod(batchMethod = "getActiveRelationMapByWorkerProjectIds")
public Relation getActiveRelationByWorkerProjectId(Long workerProjectId) {
    return relationMapper.selectActiveByWorkerProjectId(workerProjectId);
}

private Map<Long, Relation> getActiveRelationMapByWorkerProjectIds(Collection<Long> workerProjectIds) {
    return relationMapper.selectActiveByWorkerProjectIds(workerProjectIds)
            .stream()
            .collect(Collectors.toMap(Relation::getWorkerProjectId, Function.identity()));
}
```

多参数调用继续使用强类型方法引用：

```java
BatchRef.wrap(
        gcUserQueries::getActiveUserByWorkerProjectIdAndUserId,
        project.getProjectId(),
        param.getUserId()
);
```

如果希望在 key 上保留参数名，可以把多个参数收成一个 record：

```java
record ActiveUserQuery(Long workerProjectId, Long userId) {
}

BatchRef.wrap(gcUserQueries::getActiveUser, new ActiveUserQuery(project.getProjectId(), param.getUserId()));
```

## 注意事项

- `batchMethod` 写错会在 IDEA 插件里标红，也会在编译期由注解处理器报错并终止编译。
- 批量方法必须只有一个 `Collection` 参数，并返回 `Map`。
- `loaderName`、key 和 fallback 不需要手写，框架会根据类名、方法签名和入参自动生成。
- `@BatchScope` 方法正常返回前会自动 flush；不要在业务代码里手动调用 `BatchRefs.flush()`。
- `@BatchScope` 内使用 `BatchRef.wrap(service::method, args...)` 这种直接方法引用；不要写成 `BatchRef.wrap(() -> service.method(arg))`。
- `BatchRef` 不提供 `get()`；需要写值用 `setOut(...)`，需要读取真实对象用 `whenPresent(value -> ...)`。

MyBatis Plus 示例见 [docs/mybatis-plus.md](docs/mybatis-plus.md)。
