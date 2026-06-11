package io.github.batchref;

import io.github.batchref.autoconfigure.BatchRefAutoConfiguration;
import io.github.batchref.annotation.BatchScope;
import io.github.batchref.spring.BatchScopeAspect;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRefAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, BatchRefAutoConfiguration.class));

    @Test
    void createsBatchScopeAspectByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(BatchScopeAspect.class));
    }

    @Test
    void canDisableAutoConfiguration() {
        contextRunner.withPropertyValues("batch-ref.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BatchScopeAspect.class));
    }

    @Test
    void batchScopeAutoFlushesFinalBusinessCodeWithoutExposingKeys() {
        contextRunner.withUserConfiguration(FinalBusinessCodeConfiguration.class)
                .run(context -> {
                    ProjectService projectService = context.getBean(ProjectService.class);
                    ProjectGcRelationQueryService relationQueryService =
                            context.getBean(ProjectGcRelationQueryService.class);
                    ProjectGcUserQueryService gcUserQueryService =
                            context.getBean(ProjectGcUserQueryService.class);

                    List<ProjectVO> projectList = projectService.getProjectList(new ProjectListParam(20L));

                    assertThat(projectList).hasSize(2);
                    ProjectVO relatedProject = projectList.get(0);
                    assertThat(relatedProject.getRelatedToGc()).isTrue();
                    assertThat(relatedProject.getGcProjectId()).isEqualTo(100L);
                    assertThat(relatedProject.getGcRelationId()).isEqualTo(200L);
                    assertThat(relatedProject.getNeedGcApproval()).isTrue();
                    assertThat(relatedProject.getGcRelationStatusName()).isEqualTo("正常");
                    assertThat(relatedProject.getJoinedGcProject()).isTrue();
                    assertThat(relatedProject.getGcUserId()).isEqualTo(300L);
                    assertThat(relatedProject.getGcUserType()).isEqualTo(1);
                    assertThat(relatedProject.getGcUserTypeName()).isEqualTo("正式工");

                    ProjectVO unrelatedProject = projectList.get(1);
                    assertThat(unrelatedProject.getRelatedToGc()).isFalse();
                    assertThat(unrelatedProject.getGcProjectId()).isNull();
                    assertThat(unrelatedProject.getGcRelationId()).isNull();
                    assertThat(unrelatedProject.getNeedGcApproval()).isFalse();
                    assertThat(unrelatedProject.getGcRelationStatusName()).isNull();
                    assertThat(unrelatedProject.getJoinedGcProject()).isFalse();
                    assertThat(unrelatedProject.getGcUserId()).isNull();
                    assertThat(unrelatedProject.getGcUserType()).isNull();
                    assertThat(unrelatedProject.getGcUserTypeName()).isNull();

                    assertThat(relationQueryService.batchCalls()).hasValue(1);
                    assertThat(relationQueryService.fallbackCalls()).hasValue(0);
                    assertThat(gcUserQueryService.batchCalls()).hasValue(1);
                    assertThat(gcUserQueryService.fallbackCalls()).hasValue(0);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class FinalBusinessCodeConfiguration {

        @Bean
        ProjectGcRelationQueryService projectGcRelationQueryService() {
            return new ProjectGcRelationQueryService();
        }

        @Bean
        ProjectGcUserQueryService projectGcUserQueryService() {
            return new ProjectGcUserQueryService();
        }

        @Bean
        ProjectService projectService(
                ProjectGcRelationQueryService projectGcRelationQueryService,
                ProjectGcUserQueryService projectGcUserQueryService
        ) {
            return new ProjectService(projectGcRelationQueryService, projectGcUserQueryService);
        }
    }

    static class ProjectService {

        private final ProjectGcRelationQueryService projectGcRelationQueryService;
        private final ProjectGcUserQueryService projectGcUserQueryService;

        ProjectService(
                ProjectGcRelationQueryService projectGcRelationQueryService,
                ProjectGcUserQueryService projectGcUserQueryService
        ) {
            this.projectGcRelationQueryService = projectGcRelationQueryService;
            this.projectGcUserQueryService = projectGcUserQueryService;
        }

        @BatchScope
        public List<ProjectVO> getProjectList(ProjectListParam param) {
            List<ProjectVO> projectList = queryProjectList(param);

            for (ProjectVO project : projectList) {
                BatchRef<GeneralContractingProjectGroupRelation> relationRef =
                        BatchRef.wrap(
                                projectGcRelationQueryService.activeRelationByWorkerProjectId(
                                        project.getProjectId()
                                )
                        );

                BatchRef<GeneralContractingProjectUser> gcUserRef =
                        BatchRef.wrap(
                                projectGcUserQueryService.activeUserByWorkerProjectIdAndUserId(
                                        project.getProjectId(),
                                        param.getUserId()
                                )
                        );

                fillGcInfo(project, relationRef, gcUserRef);
            }

            return projectList;
        }

        private List<ProjectVO> queryProjectList(ProjectListParam param) {
            return List.of(new ProjectVO(10L), new ProjectVO(404L));
        }

        private void fillGcInfo(
                ProjectVO project,
                BatchRef<GeneralContractingProjectGroupRelation> relationRef,
                BatchRef<GeneralContractingProjectUser> gcUserRef
        ) {
            relationRef.whenAbsent(() -> {
                project.setRelatedToGc(false);
                project.setGcProjectId(null);
                project.setGcRelationId(null);
                project.setNeedGcApproval(false);
                project.setGcRelationStatusName(null);

                project.setJoinedGcProject(false);
                project.setGcUserId(null);
                project.setGcUserType(null);
                project.setGcUserTypeName(null);
            });

            relationRef.whenPresent(() -> project.setRelatedToGc(true));

            relationRef.setOut(
                    GeneralContractingProjectGroupRelation::getGcProjectId,
                    project::setGcProjectId
            );

            relationRef.setOut(
                    GeneralContractingProjectGroupRelation::getId,
                    project::setGcRelationId
            );

            relationRef.setOutMapped(
                    GeneralContractingProjectGroupRelation::getNeedApproval,
                    Boolean.TRUE::equals,
                    project::setNeedGcApproval
            );

            relationRef.setOutMapped(
                    GeneralContractingProjectGroupRelation::getStatus,
                    status -> Objects.equals(status, 1) ? "正常" : "已停用",
                    project::setGcRelationStatusName
            );

            gcUserRef.whenAbsent(() -> {
                project.setJoinedGcProject(false);
                project.setGcUserId(null);
                project.setGcUserType(null);
                project.setGcUserTypeName(null);
            });

            gcUserRef.whenPresent(() -> project.setJoinedGcProject(true));

            gcUserRef.setOut(
                    GeneralContractingProjectUser::getId,
                    project::setGcUserId
            );

            gcUserRef.setOut(
                    GeneralContractingProjectUser::getUserType,
                    project::setGcUserType
            );

            gcUserRef.setOutMapped(
                    GeneralContractingProjectUser::getUserType,
                    this::toGcUserTypeName,
                    project::setGcUserTypeName
            );
        }

        private String toGcUserTypeName(Integer userType) {
            if (Objects.equals(userType, 1)) {
                return "正式工";
            }

            if (Objects.equals(userType, 2)) {
                return "临时工";
            }

            return "未知";
        }
    }

    static class ProjectGcRelationQueryService {

        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final Map<Long, GeneralContractingProjectGroupRelation> relations = Map.of(
                10L, new GeneralContractingProjectGroupRelation(100L, 200L, true, 1)
        );

        BatchQuery<GeneralContractingProjectGroupRelation> activeRelationByWorkerProjectId(Long workerProjectId) {
            return BatchQuery.ofTyped(
                    "relation.activeByWorkerProjectId",
                    workerProjectId,
                    this::getActiveRelationMapByWorkerProjectIds,
                    () -> getActiveRelationByWorkerProjectId(workerProjectId)
            );
        }

        private Map<Long, GeneralContractingProjectGroupRelation> getActiveRelationMapByWorkerProjectIds(
                Collection<Long> workerProjectIds
        ) {
            batchCalls.incrementAndGet();
            Map<Long, GeneralContractingProjectGroupRelation> loaded = new LinkedHashMap<>();
            for (Long workerProjectId : workerProjectIds) {
                if (relations.containsKey(workerProjectId)) {
                    loaded.put(workerProjectId, relations.get(workerProjectId));
                }
            }
            return loaded;
        }

        private GeneralContractingProjectGroupRelation getActiveRelationByWorkerProjectId(Long workerProjectId) {
            fallbackCalls.incrementAndGet();
            return relations.get(workerProjectId);
        }

        private AtomicInteger batchCalls() {
            return batchCalls;
        }

        private AtomicInteger fallbackCalls() {
            return fallbackCalls;
        }
    }

    static class ProjectGcUserQueryService {

        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final Map<WorkerProjectUserKey, GeneralContractingProjectUser> users = Map.of(
                new WorkerProjectUserKey(10L, 20L), new GeneralContractingProjectUser(300L, 1)
        );

        BatchQuery<GeneralContractingProjectUser> activeUserByWorkerProjectIdAndUserId(Long workerProjectId, Long userId) {
            WorkerProjectUserKey queryKey = new WorkerProjectUserKey(workerProjectId, userId);
            return BatchQuery.ofTyped(
                    "gcUser.activeByWorkerProjectIdAndUserId",
                    queryKey,
                    this::getActiveUserMapByWorkerProjectIdAndUserIds,
                    () -> getActiveUserByWorkerProjectIdAndUserId(workerProjectId, userId)
            );
        }

        private Map<WorkerProjectUserKey, GeneralContractingProjectUser> getActiveUserMapByWorkerProjectIdAndUserIds(
                Collection<WorkerProjectUserKey> queryKeys
        ) {
            batchCalls.incrementAndGet();
            Map<WorkerProjectUserKey, GeneralContractingProjectUser> loaded = new LinkedHashMap<>();
            for (WorkerProjectUserKey queryKey : queryKeys) {
                if (users.containsKey(queryKey)) {
                    loaded.put(queryKey, users.get(queryKey));
                }
            }
            return loaded;
        }

        private GeneralContractingProjectUser getActiveUserByWorkerProjectIdAndUserId(Long workerProjectId, Long userId) {
            fallbackCalls.incrementAndGet();
            return users.get(new WorkerProjectUserKey(workerProjectId, userId));
        }

        private AtomicInteger batchCalls() {
            return batchCalls;
        }

        private AtomicInteger fallbackCalls() {
            return fallbackCalls;
        }
    }

    private record ProjectListParam(Long userId) {

        private Long getUserId() {
            return userId;
        }
    }

    private record WorkerProjectUserKey(Long workerProjectId, Long userId) {
    }

    static class GeneralContractingProjectGroupRelation {
        private final Long gcProjectId;
        private final Long id;
        private final Boolean needApproval;
        private final Integer status;

        GeneralContractingProjectGroupRelation(Long gcProjectId, Long id, Boolean needApproval, Integer status) {
            this.gcProjectId = gcProjectId;
            this.id = id;
            this.needApproval = needApproval;
            this.status = status;
        }

        private Long getGcProjectId() {
            return gcProjectId;
        }

        private Long getId() {
            return id;
        }

        private Boolean getNeedApproval() {
            return needApproval;
        }

        private Integer getStatus() {
            return status;
        }
    }

    static class GeneralContractingProjectUser {
        private final Long id;
        private final Integer userType;

        GeneralContractingProjectUser(Long id, Integer userType) {
            this.id = id;
            this.userType = userType;
        }

        private Long getId() {
            return id;
        }

        private Integer getUserType() {
            return userType;
        }
    }

    static class ProjectVO {
        private final Long projectId;
        private Boolean relatedToGc;
        private Long gcProjectId;
        private Long gcRelationId;
        private Boolean needGcApproval;
        private String gcRelationStatusName;
        private Boolean joinedGcProject;
        private Long gcUserId;
        private Integer gcUserType;
        private String gcUserTypeName;

        ProjectVO(Long projectId) {
            this.projectId = projectId;
        }

        private Long getProjectId() {
            return projectId;
        }

        private Boolean getRelatedToGc() {
            return relatedToGc;
        }

        private void setRelatedToGc(Boolean relatedToGc) {
            this.relatedToGc = relatedToGc;
        }

        private Long getGcProjectId() {
            return gcProjectId;
        }

        private void setGcProjectId(Long gcProjectId) {
            this.gcProjectId = gcProjectId;
        }

        private Long getGcRelationId() {
            return gcRelationId;
        }

        private void setGcRelationId(Long gcRelationId) {
            this.gcRelationId = gcRelationId;
        }

        private Boolean getNeedGcApproval() {
            return needGcApproval;
        }

        private void setNeedGcApproval(Boolean needGcApproval) {
            this.needGcApproval = needGcApproval;
        }

        private String getGcRelationStatusName() {
            return gcRelationStatusName;
        }

        private void setGcRelationStatusName(String gcRelationStatusName) {
            this.gcRelationStatusName = gcRelationStatusName;
        }

        private Boolean getJoinedGcProject() {
            return joinedGcProject;
        }

        private void setJoinedGcProject(Boolean joinedGcProject) {
            this.joinedGcProject = joinedGcProject;
        }

        private Long getGcUserId() {
            return gcUserId;
        }

        private void setGcUserId(Long gcUserId) {
            this.gcUserId = gcUserId;
        }

        private Integer getGcUserType() {
            return gcUserType;
        }

        private void setGcUserType(Integer gcUserType) {
            this.gcUserType = gcUserType;
        }

        private String getGcUserTypeName() {
            return gcUserTypeName;
        }

        private void setGcUserTypeName(String gcUserTypeName) {
            this.gcUserTypeName = gcUserTypeName;
        }
    }
}
