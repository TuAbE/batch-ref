package io.github.batchref;

import io.github.batchref.autoconfigure.BatchRefAutoConfiguration;
import io.github.batchref.annotation.BatchQueryMethod;
import io.github.batchref.annotation.BatchScope;
import io.github.batchref.spring.BatchQueryMethodAspect;
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

    private static final Long BUILDING_A_WORKER_PROJECT_ID = 2024060101L;
    private static final Long BASEMENT_WORKER_PROJECT_ID = 2024060102L;
    private static final Long MUNICIPAL_ROAD_WORKER_PROJECT_ID = 2024060199L;
    private static final Long APPROVER_USER_ID = 88010001L;
    private static final Long GC_HEADQUARTERS_PROJECT_ID = 90010001L;
    private static final Long GC_PARKING_PROJECT_ID = 90010002L;
    private static final Long ACTIVE_RELATION_ID = 70010001L;
    private static final Long DISABLED_RELATION_ID = 70010002L;
    private static final Long FULL_TIME_GC_USER_ID = 66010001L;
    private static final Long TEMPORARY_GC_USER_ID = 66010002L;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, BatchRefAutoConfiguration.class));

    @Test
    void createsBatchScopeAspectByDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(BatchScopeAspect.class));
        contextRunner.run(context -> assertThat(context).hasSingleBean(BatchQueryMethodAspect.class));
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

                    List<ProjectVO> projectList = projectService.getProjectList(new ProjectListParam(APPROVER_USER_ID));

                    assertThat(projectList).hasSize(3);

                    ProjectVO buildingProject = projectList.get(0);
                    assertThat(buildingProject.getProjectName()).isEqualTo("A座土建施工");
                    assertThat(buildingProject.getRelatedToGc()).isTrue();
                    assertThat(buildingProject.getGcProjectId()).isEqualTo(GC_HEADQUARTERS_PROJECT_ID);
                    assertThat(buildingProject.getGcRelationId()).isEqualTo(ACTIVE_RELATION_ID);
                    assertThat(buildingProject.getNeedGcApproval()).isTrue();
                    assertThat(buildingProject.getGcRelationStatusName()).isEqualTo("正常");
                    assertThat(buildingProject.getJoinedGcProject()).isTrue();
                    assertThat(buildingProject.getGcUserId()).isEqualTo(FULL_TIME_GC_USER_ID);
                    assertThat(buildingProject.getGcUserType()).isEqualTo(1);
                    assertThat(buildingProject.getGcUserTypeName()).isEqualTo("正式工");

                    ProjectVO basementProject = projectList.get(1);
                    assertThat(basementProject.getProjectName()).isEqualTo("地下车库机电安装");
                    assertThat(basementProject.getRelatedToGc()).isTrue();
                    assertThat(basementProject.getGcProjectId()).isEqualTo(GC_PARKING_PROJECT_ID);
                    assertThat(basementProject.getGcRelationId()).isEqualTo(DISABLED_RELATION_ID);
                    assertThat(basementProject.getNeedGcApproval()).isFalse();
                    assertThat(basementProject.getGcRelationStatusName()).isEqualTo("已停用");
                    assertThat(basementProject.getJoinedGcProject()).isTrue();
                    assertThat(basementProject.getGcUserId()).isEqualTo(TEMPORARY_GC_USER_ID);
                    assertThat(basementProject.getGcUserType()).isEqualTo(2);
                    assertThat(basementProject.getGcUserTypeName()).isEqualTo("临时工");

                    ProjectVO unrelatedProject = projectList.get(2);
                    assertThat(unrelatedProject.getProjectName()).isEqualTo("市政道路配套工程");
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

    @Test
    void annotatedQueryMethodFallsBackToSingleMethodWithoutBatchScope() {
        contextRunner.withUserConfiguration(FinalBusinessCodeConfiguration.class)
                .run(context -> {
                    ProjectGcRelationQueryService relationQueryService =
                            context.getBean(ProjectGcRelationQueryService.class);
                    ProjectVO project = new ProjectVO(BUILDING_A_WORKER_PROJECT_ID, "A座土建施工");

                    BatchRef<GeneralContractingProjectGroupRelation> relationRef = BatchRef.wrap(
                            relationQueryService::getActiveRelationByWorkerProjectId,
                            project.getProjectId()
                    );

                    relationRef.setOut(
                            GeneralContractingProjectGroupRelation::getGcProjectId,
                            project::setGcProjectId
                    );

                    assertThat(project.getGcProjectId()).isEqualTo(GC_HEADQUARTERS_PROJECT_ID);
                    assertThat(relationQueryService.batchCalls()).hasValue(0);
                    assertThat(relationQueryService.fallbackCalls()).hasValue(1);
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
                                projectGcRelationQueryService::getActiveRelationByWorkerProjectId,
                                project.getProjectId()
                        );

                BatchRef<GeneralContractingProjectUser> gcUserRef =
                        BatchRef.wrap(
                                projectGcUserQueryService::getActiveUserByWorkerProjectIdAndUserId,
                                project.getProjectId(),
                                param.getUserId()
                        );

                fillGcInfo(project, relationRef, gcUserRef);
            }

            return projectList;
        }

        private List<ProjectVO> queryProjectList(ProjectListParam param) {
            return List.of(
                    new ProjectVO(BUILDING_A_WORKER_PROJECT_ID, "A座土建施工"),
                    new ProjectVO(BASEMENT_WORKER_PROJECT_ID, "地下车库机电安装"),
                    new ProjectVO(MUNICIPAL_ROAD_WORKER_PROJECT_ID, "市政道路配套工程")
            );
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
                BUILDING_A_WORKER_PROJECT_ID,
                new GeneralContractingProjectGroupRelation(
                        GC_HEADQUARTERS_PROJECT_ID,
                        ACTIVE_RELATION_ID,
                        true,
                        1
                ),
                BASEMENT_WORKER_PROJECT_ID,
                new GeneralContractingProjectGroupRelation(
                        GC_PARKING_PROJECT_ID,
                        DISABLED_RELATION_ID,
                        false,
                        0
                )
        );

        @BatchQueryMethod
        public GeneralContractingProjectGroupRelation getActiveRelationByWorkerProjectId(Long workerProjectId) {
            fallbackCalls.incrementAndGet();
            return relations.get(workerProjectId);
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

        public AtomicInteger batchCalls() {
            return batchCalls;
        }

        public AtomicInteger fallbackCalls() {
            return fallbackCalls;
        }
    }

    static class ProjectGcUserQueryService {

        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger fallbackCalls = new AtomicInteger();
        private final Map<List<Object>, GeneralContractingProjectUser> users = Map.of(
                queryKey(BUILDING_A_WORKER_PROJECT_ID, APPROVER_USER_ID),
                new GeneralContractingProjectUser(FULL_TIME_GC_USER_ID, 1),
                queryKey(BASEMENT_WORKER_PROJECT_ID, APPROVER_USER_ID),
                new GeneralContractingProjectUser(TEMPORARY_GC_USER_ID, 2)
        );

        @BatchQueryMethod
        public GeneralContractingProjectUser getActiveUserByWorkerProjectIdAndUserId(Long workerProjectId, Long userId) {
            fallbackCalls.incrementAndGet();
            return users.get(queryKey(workerProjectId, userId));
        }

        private Map<List<Object>, GeneralContractingProjectUser> getActiveUserMapByWorkerProjectIdAndUserIds(
                Collection<List<Object>> queryKeys
        ) {
            batchCalls.incrementAndGet();
            Map<List<Object>, GeneralContractingProjectUser> loaded = new LinkedHashMap<>();
            for (List<Object> queryKey : queryKeys) {
                if (users.containsKey(queryKey)) {
                    loaded.put(queryKey, users.get(queryKey));
                }
            }
            return loaded;
        }

        private static List<Object> queryKey(Long workerProjectId, Long userId) {
            return List.of(workerProjectId, userId);
        }

        public AtomicInteger batchCalls() {
            return batchCalls;
        }

        public AtomicInteger fallbackCalls() {
            return fallbackCalls;
        }
    }

    private record ProjectListParam(Long userId) {

        private Long getUserId() {
            return userId;
        }
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
        private final String projectName;
        private Boolean relatedToGc;
        private Long gcProjectId;
        private Long gcRelationId;
        private Boolean needGcApproval;
        private String gcRelationStatusName;
        private Boolean joinedGcProject;
        private Long gcUserId;
        private Integer gcUserType;
        private String gcUserTypeName;

        ProjectVO(Long projectId, String projectName) {
            this.projectId = projectId;
            this.projectName = projectName;
        }

        private Long getProjectId() {
            return projectId;
        }

        private String getProjectName() {
            return projectName;
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
