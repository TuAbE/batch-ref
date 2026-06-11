# BatchRef Spring Boot Starter

BatchRef 用于把循环里的单个查询描述收集起来，在 `@BatchScope` 自动 flush 时按 `loaderName` 合并成批量查询，再把 `setOut`、`whenPresent`、`whenAbsent`、`whenValue` 等步骤回放到业务对象上。

完整设计稿保留在 [BatchRef.md](BatchRef.md)，业务使用方式以其中示例为准，本实现不改变文档里的调用方式。

## 环境

- Java 17
- Spring Boot 3.5.x
- MyBatis Plus ORM 项目可使用 `io.github.batchref.mybatis.MybatisPlusBatchQueries` 辅助创建 `BatchQuery`

## 引入

```xml
<dependency>
    <groupId>io.github.batchref</groupId>
    <artifactId>batch-ref-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Spring Boot 自动装配

starter 通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动装配 `BatchScopeAspect`。

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
                        projectGcRelationQueryService.activeRelationByWorkerProjectId(project.getProjectId())
                );

        relationRef.whenPresent(() -> project.setRelatedToGc(true));
        relationRef.whenAbsent(() -> project.setRelatedToGc(false));
        relationRef.setOut(
                GeneralContractingProjectGroupRelation::getGcProjectId,
                project::setGcProjectId
        );
    }

    return projectList;
}
```

没有 `@BatchScope` 时，`BatchRef.wrap(...)` 会直接调用 `fallbackLoader` 单查，并在后续登记步骤时立即执行对应动作。

## QueryService

文档里的写法保持可用：

```java
public BatchQuery<Relation> activeRelationByWorkerProjectId(Long workerProjectId) {
    return BatchQuery.of(
            "relation.activeByWorkerProjectId",
            workerProjectId,
            ids -> getActiveRelationMapByWorkerProjectIds(BatchKeys.cast(ids, Long.class)),
            () -> getActiveRelationByWorkerProjectId(workerProjectId)
    );
}
```

也可以用泛型辅助方法减少强转：

```java
return BatchQuery.ofTyped(
        "relation.activeByWorkerProjectId",
        workerProjectId,
        this::getActiveRelationMapByWorkerProjectIds,
        () -> getActiveRelationByWorkerProjectId(workerProjectId)
);
```

MyBatis Plus 示例见 [docs/mybatis-plus.md](docs/mybatis-plus.md)。
