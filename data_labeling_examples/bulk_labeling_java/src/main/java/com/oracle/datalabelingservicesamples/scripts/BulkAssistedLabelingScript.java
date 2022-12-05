package com.oracle.datalabelingservicesamples.scripts;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.oracle.bmc.aivision.model.CreateModelDetails;
import com.oracle.bmc.aivision.model.CreateProjectDetails;
import com.oracle.bmc.aivision.model.DataScienceLabelingDataset;
import com.oracle.bmc.aivision.model.Model;
import com.oracle.bmc.aivision.requests.CreateModelRequest;
import com.oracle.bmc.aivision.requests.CreateProjectRequest;
import com.oracle.bmc.aivision.responses.CreateModelResponse;
import com.oracle.bmc.aivision.responses.CreateProjectResponse;
import com.oracle.bmc.datalabelingservice.model.Dataset;
import com.oracle.bmc.datalabelingservice.model.ImageDatasetFormatDetails;
import com.oracle.bmc.datalabelingservice.model.Label;
import com.oracle.bmc.datalabelingservice.model.ObjectStorageSourceDetails;
import com.oracle.bmc.datalabelingservice.model.TextDatasetFormatDetails;
import com.oracle.bmc.datalabelingservice.requests.GetDatasetRequest;
import com.oracle.bmc.datalabelingservice.responses.GetDatasetResponse;
import com.oracle.bmc.datalabelingservicedataplane.model.Annotation;
import com.oracle.datalabelingservicesamples.constants.DataLabelingConstants;
import com.oracle.datalabelingservicesamples.labelingstrategies.AssistedLabelingStrategy;
import com.oracle.datalabelingservicesamples.labelingstrategies.MlAssistedEntityExtraction;
import com.oracle.datalabelingservicesamples.labelingstrategies.MlAssistedImageClassification;
import com.oracle.datalabelingservicesamples.labelingstrategies.MlAssistedObjectDetection;
import com.oracle.datalabelingservicesamples.labelingstrategies.MlAssistedTextClassification;
import com.oracle.datalabelingservicesamples.requests.AssistedLabelingParams;
import com.oracle.datalabelingservicesamples.requests.BucketDetails;
import com.oracle.datalabelingservicesamples.tasks.TaskHandler;
import com.oracle.datalabelingservicesamples.tasks.TaskProvider;
import com.oracle.datalabelingservicesamples.utils.DataPlaneAPIWrapper;
import org.apache.commons.collections4.ListUtils;

import com.oracle.bmc.datalabelingservicedataplane.model.CreateAnnotationDetails;
import com.oracle.bmc.datalabelingservicedataplane.model.RecordSummary;
import com.oracle.datalabelingservicesamples.requests.Config;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import static java.lang.Float.parseFloat;

/*
 *
 * This Script takes following input for Assisted Labeling:
 *
 * DATASET_ID : the id of the dataset that you want to bulk label
 * ML_MODEL_TYPE : String input specifying whether to use Pretrained/Custom models for the ai services
 * LABELING_ALGORITHM : The algorithm that will determine the label of any record.
 * 						Currently following algorithms are supported: ML_ASSISTED_IMAGE_CLASSIFICATION
 *                                                                    ML_ASSISTED_TEXT_CLASSIFICATION
 *                                                                    ML_ASSISTED_OBJECT_DETECTION
 *                                                                    ML_ASSISTED_ENTITY_EXTRACTION
 *
 *
 * Following code constraints are added:
 * 	1. The API only annotates unlabeled records. Labels that are already annotated will be skipped.
 *
 */
@Slf4j
public class BulkAssistedLabelingScript {

    static ExecutorService executorService;
    static ExecutorService annotationExecutorService;
    static Dataset dataset;
    private static AssistedLabelingParams assistedLabelingParams;
    private static final TaskHandler taskHandler = new TaskHandler(new TaskProvider());
    private static final DataPlaneAPIWrapper dataPlaneAPIWrapper = new DataPlaneAPIWrapper();
    private static AssistedLabelingStrategy assistedLabelingStrategy = null;
    static List<String> successRecordIds = Collections.synchronizedList(new ArrayList<String>());
    static List<String> failedRecordIds = Collections.synchronizedList(new ArrayList<String>());

    static {
        executorService = Executors.newFixedThreadPool(Config.INSTANCE.getThreadCount());
        annotationExecutorService = Executors.newFixedThreadPool(Config.INSTANCE.getThreadCount());
    }

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        long startTime = System.nanoTime();
        String datasetId = Config.INSTANCE.getDatasetId();
        preprocessAssistedLabelingRequest(datasetId);
        DataPlaneAPIWrapper dpAPIWrapper = new DataPlaneAPIWrapper();

        // Initialize parameters required for bulk assisted labeling
        float confidenceScore = parseFloat(Config.INSTANCE.getConfidenceThreshold());

        ObjectStorageSourceDetails sourceDetails = (ObjectStorageSourceDetails) dataset.getDatasetSourceDetails();

        List<String> dlsDatasetLabels = new ArrayList<>();
        try {
            dataset.getLabelSet().getItems().stream()
                    .forEach(
                            LabelName -> {
                                dlsDatasetLabels.add(LabelName.getName());
                            });
        } catch (Exception e) {
            log.error("Exception in getting labels from dataset {}", datasetId);
            return;
        }

        assistedLabelingParams = AssistedLabelingParams.builder()
                .datasetId(datasetId)
                .compartmentId(dataset.getCompartmentId())
                .annotationFormat(dataset.getAnnotationFormat())
                .assistedLabelingTimeout(DataLabelingConstants.ASSISTED_LABELING_TIMEOUT)
                .mlModelType(Config.INSTANCE.getMlModelType())
                .customModelId(Config.INSTANCE.getMlModelType().equals("CUSTOM") ? Config.INSTANCE.getCustomModelId() : null)
                .confidenceThreshold(confidenceScore)
                .dlsDatasetLabels(dlsDatasetLabels)
                .customerBucket(BucketDetails.builder()
                        .bucketName(sourceDetails.getBucket())
                        .namespace(sourceDetails.getNamespace())
                        .prefix(sourceDetails.getPrefix())
                        .region(Config.INSTANCE.getRegion())
                        .build())
                .customTrainingEnabled(Config.INSTANCE.getCustomTrainingEnabled().equalsIgnoreCase("true"))
                .build();

        log.info("Starting Assisted Labeling for dataset: {}", dataset.getDisplayName());

        if(assistedLabelingParams.isCustomTrainingEnabled()){
            performCustomModelTraining(assistedLabelingParams);
        }

        // 1. List existing record files
        log.info("List Dataset Records");
        List<RecordSummary> existingRecords = null;
        try {
            existingRecords =
                    dpAPIWrapper.listRecords(
                            datasetId,
                            dataset.getCompartmentId(),
                            true);
            log.info(
                    "For dataset {}, found {} existing records.",
                    datasetId,
                    existingRecords.size());
        } catch (Exception e) {
            log.error(
                    "Failed to list existing records for dataset",
                    e);
        }

        // 2. Get unlabelled records
        List<RecordSummary> recordsForAssistedLabelling =
                existingRecords.stream()
                        .filter(
                                recordSummary ->
                                        !recordSummary.getIsLabeled())
                        .collect(Collectors.toList());

        // 3. Create batch requests to downstream AI service for predictions
        int maxDownstreamBatchrequest = 8;
        List<List<RecordSummary>> recordRequestsInBatches =
                ListUtils.partition(recordsForAssistedLabelling, maxDownstreamBatchrequest);

        try {
            createAndWaitForAssistedLabelTasks(recordRequestsInBatches, assistedLabelingParams, executorService);
            executorService.shutdown();
            annotationExecutorService.shutdown();
            log.info("Time Taken for datasetId {}", datasetId);
            log.info("Successfully Annotated {} record Ids", successRecordIds.size());
            log.info("Create annotation failed for record Ids {}", failedRecordIds);
            long elapsedTime = System.nanoTime() - startTime;
            log.info("Time Taken for datasetId {} is {} seconds", datasetId, elapsedTime / 1_000_000_000);
        } catch (Exception e) {
            log.error(
                    "Failed while making downstream API calls",
                    e);
        }
}

    private static void createAndWaitForAssistedLabelTasks(
            List<List<RecordSummary>> recordSummaries,
            AssistedLabelingParams assistedLabelingParams,
            ExecutorService executorService) {
        List<Future<List<CreateAnnotationDetails>>> getAssistedLabellingTasks =
                taskHandler.getAssistedLabelTasks(
                        recordSummaries,
                        assistedLabelingParams,
                        assistedLabelingStrategy,
                        executorService);

        List<CreateAnnotationDetails> createAnnotationDetailsList = new ArrayList<>();
        taskHandler.waitForTasks(
                getAssistedLabellingTasks,
                createAnnotationDetailsResponse -> {
                    if (createAnnotationDetailsResponse != null
                            && !createAnnotationDetailsResponse.isEmpty()) {
                        createAnnotationDetailsList.addAll(createAnnotationDetailsResponse);
                        log.info(
                                "createAnnotationDetailsList size : {}",
                                createAnnotationDetailsList.size());
                    }
                },
                exception -> {
                    // TODO - Changes required here
                    //                    if (exception.getCause() instanceof BmcException) {
                    //                        BmcException bmcException = (BmcException)
                },
                assistedLabelingParams.getAssistedLabelingTimeout());
        log.info("Coming here after thread finished");
        createAndWaitForAnnotationTasksToComplete(createAnnotationDetailsList);
    }

    private static void createAndWaitForAnnotationTasksToComplete(
            List<CreateAnnotationDetails> createAnnotationDetailsList) {
        List<Future<Annotation>> createAnnotationTasks =
                taskHandler.getCreateAnnotationTasks(
                        createAnnotationDetailsList,
                        dataPlaneAPIWrapper,
                        "opcRequestId",
                        annotationExecutorService);
        taskHandler.waitForTasks(
                createAnnotationTasks,
                annotation -> {
                    log.info(
                            "Assisted labels for {} created successfully. Record Id :{}",
                            annotation.getRecordId(),
                            annotation.getId());
                    successRecordIds.add(annotation.getRecordId());
                },
                exception -> {
                    if(exception.getMessage().contains("recordId")){
                        failedRecordIds.add(StringUtils.substringAfter(exception.getMessage(), "recordId: "));
                    }

                    // TODO - Changes required here
                    //                    if (exception.getCause() instanceof BmcException) {
                    //                        BmcException bmcException = (BmcException)
                },
                assistedLabelingParams.getAssistedLabelingTimeout());
    }

    private static void preprocessAssistedLabelingRequest(String datasetId) {
        /*
         * Validate Dataset
         */
        GetDatasetRequest getDatasetRequest = GetDatasetRequest.builder().datasetId(datasetId).build();
        GetDatasetResponse datasetResponse = Config.INSTANCE.getDlsCpClient().getDataset(getDatasetRequest);
        assert datasetResponse.get__httpStatusCode__() == 200 : "Invalid Dataset Id Provided";
        List<Label> datasetLabelSet = datasetResponse.getDataset().getLabelSet().getItems();

        /*
         * Validate Input Label Set
         */
        Set<String> actualLabels = new HashSet<>();
        for (Label labelName : datasetLabelSet) {
            actualLabels.add(labelName.getName());
        }

        /*
         * Validate custom model id
         */
        if (Config.INSTANCE.getMlModelType().equals("CUSTOM")) {
            if(Config.INSTANCE.getCustomModelId().isEmpty()) {
                log.error("Custom model ID cannot be empty when ML model type is custom");
                throw new InvalidParameterException("Custom model ID cannot be empty");
            }
        }

        dataset = datasetResponse.getDataset();

        /*
         * Initialise labeling algorithm
         */
        String labelingAlgorithm = Config.INSTANCE.getLabelingAlgorithm();
        if(!labelingAlgorithm.equals("ML_ASSISTED_LABELING")){
            log.error("Invalid algorithm for ML assisted labeling");
            throw new InvalidParameterException("Invalid algorithm for ML assisted labeling");
        }



        if(dataset.getDatasetFormatDetails() instanceof ImageDatasetFormatDetails) {
            if (dataset.getAnnotationFormat().equals("SINGLE_LABEL") || dataset.getAnnotationFormat().equals("MULTI_LABEL")) {
                assistedLabelingStrategy = new MlAssistedImageClassification();
            } else if (dataset.getAnnotationFormat().equals("OBJECT_DETECTION")) {
                assistedLabelingStrategy = new MlAssistedObjectDetection();
            }
            else{
                log.error("Invalid annotation format for ML assisted labeling");
                throw new InvalidParameterException("Invalid annotation format for ML assisted labeling");
            }
        }
        else if(dataset.getDatasetFormatDetails() instanceof TextDatasetFormatDetails){
            if(dataset.getAnnotationFormat().equals("SINGLE_LABEL")||dataset.getAnnotationFormat().equals("MULTI_LABEL")) {
                assistedLabelingStrategy = new MlAssistedTextClassification();
            }
            else if(dataset.getAnnotationFormat().equals("ENTITY_EXTRACTION")){
                assistedLabelingStrategy = new MlAssistedEntityExtraction();
            }
            else{
                log.error("Invalid annotation format for ML assisted labeling");
                throw new InvalidParameterException("Invalid annotation format for ML assisted labeling");
            }
        }
        else{
            log.error("Invalid dataset format type for ML assisted labeling");
            throw new InvalidParameterException("Invalid dataset format type for ML assisted labeling");
        }
    }

    public static void performCustomModelTraining(AssistedLabelingParams assistedLabelingParams){
        createVisionModel(createVisionProject(assistedLabelingParams), assistedLabelingParams);
    }

    public static CreateProjectResponse createVisionProject(AssistedLabelingParams assistedLabelingParams){

        CreateProjectResponse response = null;
        log.info("Starting Custom model training using input dataset: {}", dataset.getDisplayName());

        try {
            /* Create a request and dependent object(s). */
            CreateProjectDetails createProjectDetails = CreateProjectDetails.builder()
                    .displayName("test-project")
                    .description("Project for testing custom model training")
                    .compartmentId(assistedLabelingParams.getCompartmentId())
//                .freeformTags(new HashMap<String, String>() {
//                    {
//                        put("datasetId",assistedLabelingParams.getDatasetId());
//                    }
//                })
//                .definedTags(new HashMap<String, Map<String, Object>>() {
//                    {
//                        put("EXAMPLE_KEY_eKLnn",new HashMap<java.lang.String, java.lang.Object>() {
//                            {
//                                put("EXAMPLE_KEY_9a8GL","EXAMPLE--Value");
//                            }
//                        });
//                    }
//                })
                    .build();

            CreateProjectRequest createProjectRequest = CreateProjectRequest.builder()
                    .createProjectDetails(createProjectDetails)
                    .opcRetryToken("EXAMPLE-opcRetryToken-Value")
                    .opcRequestId("BulkAssistedLabeling").build();

            /* Send request to the Client */
            response = Config.INSTANCE.getAiVisionClient().createProject(createProjectRequest);
        }
        catch (Exception e){
            log.error("Failed to create new project in vision service");
        }
        return response;
    }

    public static CreateModelResponse createVisionModel(CreateProjectResponse createProjectResponse, AssistedLabelingParams assistedLabelingParams){
        CreateModelResponse modelResponse = null;

        try {
            /* Create a request and dependent object(s). */
            CreateModelDetails createModelDetails = CreateModelDetails.builder()
                    .displayName("test-model")
                    .description("Test custom model training")
//                .modelVersion("EXAMPLE-modelVersion-Value") Going with default value
                    .modelType(Model.ModelType.ImageClassification)
                    .compartmentId(assistedLabelingParams.getCompartmentId())
                    .isQuickMode(true)
                    .maxTrainingDurationInHours(1757.9204)
                    .trainingDataset(DataScienceLabelingDataset.builder()
                            .datasetId(assistedLabelingParams.getDatasetId()).build())
                    .projectId(createProjectResponse.getProject().getId())
                    .freeformTags(new HashMap<java.lang.String, java.lang.String>() {
                        {
                            put("datasetId", assistedLabelingParams.getDatasetId());
                        }
                    })
//                .definedTags(new HashMap<java.lang.String, java.util.Map<java.lang.String, java.lang.Object>>() {
//                    {
//                        put("EXAMPLE_KEY_XbxiZ",new HashMap<java.lang.String, java.lang.Object>() {
//                            {
//                                put("EXAMPLE_KEY_v3Chd","EXAMPLE--Value");
//                            }
//                        });
//                    }
//                })
                    .build();

            CreateModelRequest createModelRequest = CreateModelRequest.builder()
                    .createModelDetails(createModelDetails)
                    .opcRetryToken("EXAMPLE-opcRetryToken-Value")
                    .opcRequestId("BulkAssistedLabeling").build();

            /* Send request to the Client */
            modelResponse = Config.INSTANCE.getAiVisionClient().createModel(createModelRequest);

            assistedLabelingParams.setCustomModelId(modelResponse.getModel().getId());
        }
        catch (Exception e){
            log.error("Failed to train model in vision service", e);
        }
        return modelResponse;
    }
}
