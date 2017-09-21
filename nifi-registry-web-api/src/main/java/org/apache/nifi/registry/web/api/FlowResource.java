/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.registry.web.api;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.registry.flow.VersionedFlow;
import org.apache.nifi.registry.flow.VersionedFlowSnapshot;
import org.apache.nifi.registry.flow.VersionedFlowSnapshotMetadata;
import org.apache.nifi.registry.service.RegistryService;
import org.apache.nifi.registry.service.params.QueryParameters;
import org.apache.nifi.registry.service.params.SortParameter;
import org.apache.nifi.registry.web.link.LinkService;
import org.apache.nifi.registry.web.response.FieldsEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;

@Component
@Path("/flows")
@Api(
        value = "/flows",
        description = "Create named flows that can be versioned. Search for and retrieve existing flows."
)
public class FlowResource {

    private static final Logger logger = LoggerFactory.getLogger(FlowResource.class);

    @Context
    UriInfo uriInfo;

    private final LinkService linkService;

    private final RegistryService registryService;

    @Autowired
    public FlowResource(final RegistryService registryService, final LinkService linkService) {
        this.registryService = registryService;
        this.linkService = linkService;
    }

    @GET
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get metadata for all flows in all buckets that the registry has stored for which the client is authorized. The information about " +
                    "the versions of each flow should be obtained by requesting a specific flow by id.",
            response = VersionedFlow.class,
            responseContainer = "List"
    )
    public Response getFlows(
            @ApiParam(value = SortParameter.API_PARAM_DESCRIPTION, format = "field:order", allowMultiple = true, example = "name:ASC")
            @QueryParam("sort")
            final List<String> sortParameters) {

        final QueryParameters.Builder paramsBuilder = new QueryParameters.Builder();
        for (String sortParam : sortParameters) {
            paramsBuilder.addSort(SortParameter.fromString(sortParam));
        }

        final List<VersionedFlow> flows = registryService.getFlows(paramsBuilder.build());
        linkService.populateFlowLinks(flows);

        return Response.status(Response.Status.OK).entity(flows).build();
    }

    @GET
    @Path("/{flowId}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get metadata for an existing flow the registry has stored. If verbose is true, then the metadata " +
                    "about all snapshots for the flow will also be returned.",
            response = VersionedFlow.class
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
            }
    )
    public Response getFlow(@PathParam("flowId") final String flowId,
                            @QueryParam("verbose") @DefaultValue("false") boolean verbose) {

        final VersionedFlow flow = registryService.getFlow(flowId, verbose);
        linkService.populateFlowLinks(flow);

        if (flow.getSnapshotMetadata() != null) {
            linkService.populateSnapshotLinks(flow.getSnapshotMetadata());
        }

        return Response.status(Response.Status.OK).entity(flow).build();
    }

    @PUT
    @Path("/{flowId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Update an existing flow the registry has stored.",
            response = VersionedFlow.class
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
            }
    )
    public Response updateFlow(@PathParam("flowId") final String flowId, final VersionedFlow flow) {
        if (StringUtils.isBlank(flowId)) {
            throw new IllegalArgumentException("Flow Id cannot be blank");
        }

        if (flow == null) {
            throw new IllegalArgumentException("Flow cannot be null");
        }

        if (flow.getIdentifier() != null && !flowId.equals(flow.getIdentifier())) {
            throw new IllegalArgumentException("Flow id in path param must match flow id in body");
        }

        if (flow.getIdentifier() == null) {
            flow.setIdentifier(flowId);
        }

        final VersionedFlow updatedFlow = registryService.updateFlow(flow);
        return Response.status(Response.Status.OK).entity(updatedFlow).build();
    }

    @DELETE
    @Path("/{flowId}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Delete an existing flow the registry has stored.",
            response = VersionedFlow.class
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
            }
    )
    public Response deleteFlow(@PathParam("flowId") final String flowId) {
        final VersionedFlow deletedFlow = registryService.deleteFlow(flowId);
        return Response.status(Response.Status.OK).entity(deletedFlow).build();
    }

    @POST
    @Path("/{flowId}/versions")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Create the next version of a given flow ID. " +
            "The version number is created by the server and a location URI for the created version resource is returned.",
            response = VersionedFlowSnapshot.class
    )
    public Response createFlowVersion(@PathParam("flowId") final String flowId, final VersionedFlowSnapshot snapshot) {
        if (StringUtils.isBlank(flowId)) {
            throw new IllegalArgumentException("Flow Id cannot be blank");
        }

        if (snapshot == null) {
            throw new IllegalArgumentException("VersionedFlowSnapshot cannot be null");
        }

        if (snapshot.getSnapshotMetadata() != null && snapshot.getSnapshotMetadata().getFlowIdentifier() != null
                && !flowId.equals(snapshot.getSnapshotMetadata().getFlowIdentifier())) {
            throw new IllegalArgumentException("Flow id in path param must match flow id in body");
        }

        if (snapshot.getSnapshotMetadata() != null && snapshot.getSnapshotMetadata().getFlowIdentifier() != null) {
            snapshot.getSnapshotMetadata().setFlowIdentifier(flowId);
        }

        final VersionedFlowSnapshot createdSnapshot = registryService.createFlowSnapshot(snapshot);
        return Response.status(Response.Status.OK).entity(createdSnapshot).build();
    }

    @GET
    @Path("/{flowId}/versions")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get summary of all versions of a flow for a given flow ID.",
            response = VersionedFlowSnapshotMetadata.class,
            responseContainer = "List"
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
            }
    )
    public Response getFlowVersions(@PathParam("flowId") final String flowId) {
        final VersionedFlow flow = registryService.getFlow(flowId, true);

        if (flow.getSnapshotMetadata() != null) {
            linkService.populateSnapshotLinks(flow.getSnapshotMetadata());
        }

        return Response.status(Response.Status.OK).entity(flow.getSnapshotMetadata()).build();
    }

    @GET
    @Path("/{flowId}/versions/latest")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get the latest version of a flow for a given flow ID",
            response = VersionedFlowSnapshot.class
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
            }
    )
    public Response getLatestFlowVersion(@PathParam("flowId") final String flowId) {
        final VersionedFlow flow = registryService.getFlow(flowId, true);

        final SortedSet<VersionedFlowSnapshotMetadata> snapshots = flow.getSnapshotMetadata();
        if (snapshots == null || snapshots.size() == 0) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final VersionedFlowSnapshotMetadata lastSnapshotMetadata = snapshots.last();

        final VersionedFlowSnapshot lastSnapshot = registryService.getFlowSnapshot(
                lastSnapshotMetadata.getFlowIdentifier(), lastSnapshotMetadata.getVersion());

        return Response.status(Response.Status.OK).entity(lastSnapshot).build();
    }

    @GET
    @Path("/{flowId}/versions/{versionNumber: \\d+}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Get a given version of a flow for a given flow ID",
            response = VersionedFlowSnapshot.class
    )
    @ApiResponses(
            value = {
                    @ApiResponse(code = 404, message = "The specified resource could not be found."),
            }
    )
    public Response getFlowVersion(
            @PathParam("flowId") final String flowId,
            @PathParam("versionNumber") final Integer versionNumber) {
        final VersionedFlowSnapshot snapshot = registryService.getFlowSnapshot(flowId, versionNumber);
        return Response.status(Response.Status.OK).entity(snapshot).build();
    }

    @GET
    @Path("fields")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(
            value = "Retrieves the available field names that can be used for searching or sorting on flows.",
            response = FieldsEntity.class
    )
    public Response getAvailableFlowFields() {
        final Set<String> flowFields = registryService.getFlowFields();
        final FieldsEntity fieldsEntity = new FieldsEntity(flowFields);
        return Response.status(Response.Status.OK).entity(fieldsEntity).build();
    }

}