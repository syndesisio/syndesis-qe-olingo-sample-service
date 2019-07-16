/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.jts.trippin.data;

import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.Edm;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.http.HttpMethod;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResourceFunction;
import org.apache.olingo.server.api.uri.UriResourceNavigation;

import com.jts.trippin.data.model.entityset.Products;
import com.jts.trippin.data.model.entityset.entity.Advertisement;
import com.jts.trippin.data.model.entityset.entity.Category;
import com.jts.trippin.service.DemoEdmProvider;
import com.jts.trippin.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Storage {

    @Getter
    final private TransactionalEntityManager manager;

    @Getter
    final private Edm edm;

    @Getter
    final private OData odata;

    private PrepopulatedData prepopulatedData;
    private StorageUtil util;

    // represent our database
    public Storage(final OData odata, final Edm edm) {
        this.odata = odata;
        this.edm = edm;
        this.manager = new TransactionalEntityManager(edm);
        this.prepopulatedData = new PrepopulatedData(manager);
        this.util = new StorageUtil(this);

        final List<Entity> productList = manager.getEntityCollection(Products.NAME);

        // creating some sample data
        prepopulatedData.initProductSampleData();
        prepopulatedData.initCategorySampleData();
        prepopulatedData.initAdvertisementSampleData();

        util.linkProductsAndCategories(productList.size());
    }

    /* PUBLIC FACADE */

    public void beginTransaction() {
        manager.beginTransaction();
    }

    public void rollbackTransaction() {
        manager.rollbackTransaction();
    }

    public void commitTransaction() {
        manager.commitTransaction();
    }

    public Entity readFunctionImportEntity(final UriResourceFunction uriResourceFunction,
        final ServiceMetadata serviceMetadata) throws ODataApplicationException {

        final EntityCollection entityCollection = readFunctionImportCollection(uriResourceFunction, serviceMetadata);
        final EdmEntityType edmEntityType = (EdmEntityType) uriResourceFunction.getFunction().getReturnType().getType();

        return Util.findEntity(edmEntityType, entityCollection, uriResourceFunction.getKeyPredicates());
    }

    public EntityCollection readFunctionImportCollection(final UriResourceFunction uriResourceFunction,
        final ServiceMetadata serviceMetadata) throws ODataApplicationException {

        if (DemoEdmProvider.FUNCTION_COUNT_CATEGORIES_FQN.getName().equals(uriResourceFunction.getFunctionImport().getName())) {
            // Get the parameter of the function
            final UriParameter parameterAmount = uriResourceFunction.getParameters().get(0);
            // Try to convert the parameter to an Integer.
            // We have to take care, that the type of parameter fits to its EDM declaration
            int amount;
            try {
                amount = Integer.parseInt(parameterAmount.getText());
            } catch (NumberFormatException e) {
                throw new ODataApplicationException("Type of parameter Amount must be Edm.Int32", HttpStatusCode.BAD_REQUEST
                    .getStatusCode(), Locale.ENGLISH);
            }

            final List<Entity> resultEntityList = new ArrayList<>();

            // Loop over all categories and check how many products are linked
            for (final Entity category : manager.getEntityCollection(Category.ES_NAME)) {
                final EntityCollection products = getRelatedEntityCollection(category, DemoEdmProvider.NAV_TO_PRODUCTS);
                if (products.getEntities().size() == amount) {
                    resultEntityList.add(category);
                }
            }

            final EntityCollection resultCollection = new EntityCollection();
            resultCollection.getEntities().addAll(resultEntityList);
            return resultCollection;
        } else {
            throw new ODataApplicationException("Function not implemented", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(),
                Locale.ROOT);
        }
    }

    public void resetDataSet() {
        resetDataSet(Integer.MAX_VALUE);
    }

    public void resetDataSet(final int amount) {
        // Replace the old lists with empty ones
        manager.clear();

        // Create new sample data
        prepopulatedData.initProductSampleData();
        prepopulatedData.initCategorySampleData();

        final List<Entity> productList = manager.getEntityCollection(Products.NAME);
        final List<Entity> categoryList = manager.getEntityCollection(Category.ES_NAME);

        // Truncate the lists
        if (amount < productList.size()) {
            final List<Entity> newProductList = new ArrayList<>(productList.subList(0, amount));
            productList.clear();
            productList.addAll(newProductList);
            // Products 0, 1 are linked to category 0
            // Products 2, 3 are linked to category 1
            // Products 4, 5 are linked to category 2
            final List<Entity> newCategoryList = new ArrayList<>(categoryList.subList(0, (amount / 2) + 1));
            categoryList.clear();
            categoryList.addAll(newCategoryList);
        }

        util.linkProductsAndCategories(amount);
    }

    public EntityCollection readEntitySetData(EdmEntitySet edmEntitySet) throws ODataApplicationException {

        if (edmEntitySet.getName().equals(Products.NAME)) {
            return util.getEntityCollection(manager.getEntityCollection(Products.NAME));
        } else if (edmEntitySet.getName().equals(Category.ES_NAME)) {
            return util.getEntityCollection(manager.getEntityCollection(Category.ES_NAME));
        } else if (edmEntitySet.getName().equals(Advertisement.ES_NAME)) {
            return util.getEntityCollection(manager.getEntityCollection(Advertisement.ES_NAME));
        }

        return null;
    }

    public Entity readEntityData(EdmEntitySet edmEntitySet, List<UriParameter> keyParams)
        throws ODataApplicationException {

        EdmEntityType edmEntityType = edmEntitySet.getEntityType();

        if (edmEntitySet.getName().equals(Products.NAME)) {
            return util.getEntity(edmEntityType, keyParams, manager.getEntityCollection(Products.NAME));
        } else if (edmEntitySet.getName().equals(Category.ES_NAME)) {
            return util.getEntity(edmEntityType, keyParams, manager.getEntityCollection(Category.ES_NAME));
        } else if (edmEntitySet.getName().equals(Advertisement.ES_NAME)) {
            return util.getEntity(edmEntityType, keyParams, manager.getEntityCollection(Advertisement.ES_NAME));
        }

        return null;
    }

    // Navigation

    public Entity getRelatedEntity(Entity entity, UriResourceNavigation navigationResource)
        throws ODataApplicationException {

        final EdmNavigationProperty edmNavigationProperty = navigationResource.getProperty();

        if (edmNavigationProperty.isCollection()) {
            return Util.findEntity(edmNavigationProperty.getType(), getRelatedEntityCollection(entity, navigationResource),
                navigationResource.getKeyPredicates());
        } else {
            final Link link = entity.getNavigationLink(edmNavigationProperty.getName());
            return link == null ? null : link.getInlineEntity();
        }
    }

    public EntityCollection getRelatedEntityCollection(Entity entity, UriResourceNavigation navigationResource) {
        return getRelatedEntityCollection(entity, navigationResource.getProperty().getName());
    }

    public EntityCollection getRelatedEntityCollection(Entity entity, String navigationPropertyName) {
        final Link link = entity.getNavigationLink(navigationPropertyName);
        return link == null ? new EntityCollection() : link.getInlineEntitySet();
    }

    public Entity createEntityData(EdmEntitySet edmEntitySet, Entity entityToCreate, String rawServiceUri)
        throws ODataApplicationException {

        EdmEntityType edmEntityType = edmEntitySet.getEntityType();

        if (edmEntitySet.getName().equals(Products.NAME)) {
            return util.createEntity(edmEntitySet, edmEntityType, entityToCreate,
                manager.getEntityCollection(Products.NAME), rawServiceUri);
        } else if (edmEntitySet.getName().equals(Category.ES_NAME)) {
            return util.createEntity(edmEntitySet, edmEntityType, entityToCreate,
                manager.getEntityCollection(Category.ES_NAME), rawServiceUri);
        }

        return null;
    }

    /**
     * This method is invoked for PATCH or PUT requests
     */
    public void updateEntityData(EdmEntitySet edmEntitySet, List<UriParameter> keyParams, Entity updateEntity,
        HttpMethod httpMethod) throws ODataApplicationException {

        EdmEntityType edmEntityType = edmEntitySet.getEntityType();

        if (edmEntitySet.getName().equals(Products.NAME)) {
            util.updateEntity(edmEntityType, keyParams, updateEntity, httpMethod,
                manager.getEntityCollection(Products.NAME));
        } else if (edmEntitySet.getName().equals(Category.ES_NAME)) {
            util.updateEntity(edmEntityType, keyParams, updateEntity, httpMethod,
                manager.getEntityCollection(Category.ES_NAME));
        } else if (edmEntitySet.getName().equals(Advertisement.ES_NAME)) {
            util.updateEntity(edmEntityType, keyParams, updateEntity, httpMethod,
                manager.getEntityCollection(Advertisement.ES_NAME));
        }
    }

    public void deleteEntityData(EdmEntitySet edmEntitySet, List<UriParameter> keyParams)
        throws ODataApplicationException {

        EdmEntityType edmEntityType = edmEntitySet.getEntityType();

        if (edmEntitySet.getName().equals(Products.NAME)) {
            util.deleteEntity(edmEntityType, keyParams, manager.getEntityCollection(Products.NAME));
        } else if (edmEntitySet.getName().equals(Category.ES_NAME)) {
            util.deleteEntity(edmEntityType, keyParams, manager.getEntityCollection(Category.ES_NAME));
        } else if (edmEntitySet.getName().equals(Advertisement.ES_NAME)) {
            util.deleteEntity(edmEntityType, keyParams, manager.getEntityCollection(Advertisement.ES_NAME));
        }
    }

    public byte[] readMedia(final Entity entity) {
        return (byte[]) entity.getProperty(Advertisement.MEDIA_PROPERTY_NAME).asPrimitive();
    }

    public void updateMedia(final Entity entity, final String mediaContentType, final byte[] data) {
        entity.getProperties().remove(entity.getProperty(Advertisement.MEDIA_PROPERTY_NAME));
        entity.addProperty(new Property(null, Advertisement.MEDIA_PROPERTY_NAME, ValueType.PRIMITIVE, data));
        entity.setMediaContentType(mediaContentType);
    }

    public Entity createMediaEntity(final EdmEntityType edmEntityType, final String mediaContentType,
        final byte[] data) {
        Entity entity = null;

        if (edmEntityType.getName().equals(Advertisement.ET_FQN.getName())) {
            entity = new Entity();
            entity.addProperty(new Property(null, "ID", ValueType.PRIMITIVE, UUID.randomUUID()));
            entity.addProperty(new Property(null, "Name", ValueType.PRIMITIVE, null));
            entity.addProperty(new Property(null, "AirDate", ValueType.PRIMITIVE, null));

            entity.setMediaContentType(mediaContentType);
            entity.addProperty(new Property(null, Advertisement.MEDIA_PROPERTY_NAME, ValueType.PRIMITIVE, data));

            manager.getEntityCollection(Advertisement.ES_NAME).add(entity);
        }

        return entity;
    }
}
