/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.ranger.plugin.store.file;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.Path;
import org.apache.ranger.authorization.hadoop.config.RangerConfiguration;
import org.apache.ranger.plugin.model.*;
import org.apache.ranger.plugin.policyresourcematcher.RangerDefaultPolicyResourceMatcher;
import org.apache.ranger.plugin.store.AbstractTagStore;
import org.apache.ranger.plugin.store.TagPredicateUtil;
import org.apache.ranger.plugin.store.TagStore;
import org.apache.ranger.plugin.util.SearchFilter;
import org.apache.ranger.plugin.util.TagServiceResources;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TagFileStore extends AbstractTagStore {
	private static final Log LOG = LogFactory.getLog(TagFileStore.class);

	public static final String PROPERTY_TAG_FILE_STORE_DIR = "ranger.tag.store.file.dir";
	protected static final String FILE_PREFIX_TAG_DEF = "ranger-tagdef-";
	protected static final String FILE_PREFIX_TAG_RESOURCE = "ranger-tag-resource-";

	private String tagDataDir = null;
	private long nextTagDefId = 0;
	private long nextTagResourceId = 0;


	private TagPredicateUtil predicateUtil = null;
	private FileStoreUtil fileStoreUtil = null;

	private volatile static TagFileStore instance = null;

	public static TagStore getInstance() {
		if (instance == null) {
			synchronized (TagFileStore.class) {
				if (instance == null) {
					instance = new TagFileStore();
					instance.initStore();
				}
			}
		}
		return instance;
	}

	TagFileStore() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.TagFileStore()");
		}

		tagDataDir = RangerConfiguration.getInstance().get(PROPERTY_TAG_FILE_STORE_DIR, "file:///etc/ranger/data");
		fileStoreUtil = new FileStoreUtil();
		predicateUtil = new TagPredicateUtil();

		if (LOG.isDebugEnabled())

		{
			LOG.debug("<== TagFileStore.TagFileStore()");
		}
	}

	@Override
	public void init() throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.init()");
		}

		super.init();
		fileStoreUtil.initStore(tagDataDir);

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.init()");
		}
	}

	protected void initStore() {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.initStore()");
		}
		fileStoreUtil.initStore(tagDataDir);

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.initStore()");
		}
	}

	@Override
	public RangerTagDef createTagDef(RangerTagDef tagDef) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.createTagDef(" + tagDef + ")");
		}

		List<RangerTagDef> existing = getTagDef(tagDef.getName());

		if (CollectionUtils.isNotEmpty(existing)) {
			throw new Exception(tagDef.getName() + ": tag-def already exists (id=" + existing.get(0).getId() + ")");
		}

		RangerTagDef ret;

		try {
			preCreate(tagDef);

			tagDef.setId(nextTagDefId);

			ret = fileStoreUtil.saveToFile(tagDef, new Path(fileStoreUtil.getDataFile(FILE_PREFIX_TAG_DEF, nextTagDefId++)), false);

			postCreate(ret);
		} catch (Exception excp) {
			LOG.warn("TagFileStore.createTagDef(): failed to save tag-def '" + tagDef.getName() + "'", excp);

			throw new Exception("failed to save tag-def '" + tagDef.getName() + "'", excp);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.createTagDef(" + tagDef + ")");
		}

		return ret;
	}

	@Override
	public RangerTagDef updateTagDef(RangerTagDef tagDef) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.updateTagDef(" + tagDef + ")");
		}

		RangerTagDef existing = null;

		if(tagDef.getId() == null) {
			List<RangerTagDef> existingDefs = getTagDef(tagDef.getName());

			if (CollectionUtils.isEmpty(existingDefs)) {
				throw new Exception("tag-def does not exist: name=" + tagDef.getName());
			}
		} else {
			existing = this.getTagDefById(tagDef.getId());

			if (existing == null) {
				throw new Exception("tag-def does not exist: id=" + tagDef.getId());
			}
		}


		RangerTagDef ret;

		try {
			preUpdate(existing);

			existing.setSource(tagDef.getSource());
			existing.setAttributeDefs(tagDef.getAttributeDefs());

			ret = fileStoreUtil.saveToFile(existing, new Path(fileStoreUtil.getDataFile(FILE_PREFIX_TAG_DEF, existing.getId())), true);

			postUpdate(existing);
		} catch (Exception excp) {
			LOG.warn("TagFileStore.updateTagDef(): failed to save tag-def '" + tagDef.getName() + "'", excp);

			throw new Exception("failed to save tag-def '" + tagDef.getName() + "'", excp);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.updateTagDef(" + tagDef + ")");
		}

		return ret;
	}

	@Override
	public void deleteTagDef(String name) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.deleteTagDef(" + name + ")");
		}

		List<RangerTagDef> existingDefs = getTagDef(name);

		if (CollectionUtils.isEmpty(existingDefs)) {
			throw new Exception("no tag-def exists with name=" + name);
		}

		try {
			for(RangerTagDef existing : existingDefs) {
				Path filePath = new Path(fileStoreUtil.getDataFile(FILE_PREFIX_TAG_DEF, existing.getId()));

				preDelete(existing);

				fileStoreUtil.deleteFile(filePath);

				postDelete(existing);
			}
		} catch (Exception excp) {
			throw new Exception("failed to delete tag-def with ID=" + name, excp);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.deleteTagDef(" + name + ")");
		}

	}

	@Override
	public List<RangerTagDef> getTagDef(String name) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getTagDef(" + name + ")");
		}

		List<RangerTagDef> ret;

		if (StringUtils.isNotBlank(name)) {
			SearchFilter filter = new SearchFilter(SearchFilter.TAG_DEF_NAME, name);

			List<RangerTagDef> tagDefs = getTagDefs(filter);

			ret = CollectionUtils.isEmpty(tagDefs) ? null : tagDefs;
		} else {
			ret = null;
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getTagDef(" + name + "): " + ret);
		}

		return ret;
	}

	@Override
	public RangerTagDef getTagDefById(Long id) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getTagDefById(" + id + ")");
		}

		RangerTagDef ret;

		if (id != null) {
			SearchFilter filter = new SearchFilter(SearchFilter.TAG_DEF_ID, id.toString());

			List<RangerTagDef> tagDefs = getTagDefs(filter);

			ret = CollectionUtils.isEmpty(tagDefs) ? null : tagDefs.get(0);
		} else {
			ret = null;
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getTagDefById(" + id + "): " + ret);
		}

		return ret;
	}

	@Override
	public List<RangerTagDef> getTagDefs(SearchFilter filter) throws Exception {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getTagDefs()");
		}

		List<RangerTagDef> ret = getAllTagDefs();

		if (CollectionUtils.isNotEmpty(ret) && filter != null && !filter.isEmpty()) {
			CollectionUtils.filter(ret, predicateUtil.getPredicate(filter));

			//Comparator<RangerBaseModelObject> comparator = getSorter(filter);

			//if(comparator != null) {
			//Collections.sort(ret, comparator);
			//}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getTagDefs(): count=" + (ret == null ? 0 : ret.size()));
		}

		return ret;
	}

	@Override
	public RangerTaggedResource createTaggedResource(RangerTaggedResource resource, boolean createOrUpdate) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.createResource(" + resource + ")");
		}

		RangerTaggedResource ret = null;
		RangerTaggedResource existing = null;
		boolean updateResource = false;

		existing = getResource(resource.getKey());

		if (existing != null) {
			if (!createOrUpdate) {
				throw new Exception("resource(s) with same specification already exists");
			} else {
				updateResource = true;
			}
		}

		if (! updateResource) {
			if (resource.getId() != null) {
				existing = getResource(resource.getId());
			}

			if (existing != null) {
				if (! createOrUpdate) {
					throw new Exception(resource.getId() + ": resource already exists (id=" + existing.getId() + ")");
				} else {
					updateResource = true;
				}
			}
		}

		try {
			if (updateResource) {
				ret = updateTaggedResource(resource);
			} else {
				preCreate(resource);

				resource.setId(nextTagResourceId);

				ret = fileStoreUtil.saveToFile(resource, new Path(fileStoreUtil.getDataFile(FILE_PREFIX_TAG_RESOURCE, nextTagResourceId++)), false);

				postCreate(ret);
			}
		} catch (Exception excp) {
			LOG.warn("TagFileStore.createResource(): failed to save resource '" + resource.getId() + "'", excp);

			throw new Exception("failed to save resource '" + resource.getId() + "'", excp);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.createResource(" + resource + ")");
		}

		return ret;
	}

	@Override
	public RangerTaggedResource updateTaggedResource(RangerTaggedResource resource) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.updateResource(" + resource + ")");
		}
		RangerTaggedResource existing = getResource(resource.getId());

		if (existing == null) {
			throw new Exception(resource.getId() + ": resource does not exist (id=" + resource.getId() + ")");
		}

		RangerTaggedResource ret = null;

		try {
			preUpdate(existing);

			existing.getKey().setResourceSpec(resource.getKey().getResourceSpec());
			existing.getKey().setServiceName(resource.getKey().getServiceName());
			existing.setTags(resource.getTags());

			ret = fileStoreUtil.saveToFile(existing, new Path(fileStoreUtil.getDataFile(FILE_PREFIX_TAG_RESOURCE, existing.getId())), true);

			postUpdate(existing);
		} catch (Exception excp) {
			LOG.warn("TagFileStore.updateTagDef(): failed to save resource '" + resource.getId() + "'", excp);

			throw new Exception("failed to save tag-def '" + resource.getId() + "'", excp);
		}


		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.updateResource(" + resource + ")");
		}
		return ret;
	}

	@Override
	public void deleteResource(Long id) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.deleteResource(" + id + ")");
		}

		RangerTaggedResource existing = getResource(id);

		if (existing == null) {
			throw new Exception("no resource exists with ID=" + id);
		}

		try {
			Path filePath = new Path(fileStoreUtil.getDataFile(FILE_PREFIX_TAG_RESOURCE, existing.getId()));

			preDelete(existing);

			fileStoreUtil.deleteFile(filePath);

			postDelete(existing);
		} catch (Exception excp) {
			throw new Exception("failed to delete resource with ID=" + id, excp);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.deleteResource(" + id + ")");
		}
	}

	@Override
	public RangerTaggedResource getResource(Long id) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getResource(" + id + ")");
		}
		RangerTaggedResource ret;

		if (id != null) {
			SearchFilter filter = new SearchFilter(SearchFilter.TAG_RESOURCE_ID, id.toString());

			List<RangerTaggedResource> resources = getResources(filter);

			ret = CollectionUtils.isEmpty(resources) ? null : resources.get(0);
		} else {
			ret = null;
		}
		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getResource(" + id + ")");
		}
		return ret;
	}

	@Override
	public RangerTaggedResource getResource(RangerTaggedResourceKey key) throws Exception {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getResource( " + key.getServiceName() + " )");
		}

		if (this.svcStore == null) {
			LOG.error("TagFileStore.getResource() - TagFileStore object does not have reference to a valid ServiceStore.");
			throw new Exception("TagFileStore.getResources() - TagFileStore object does not have reference to a valid ServiceStore.");
		}

		if (key == null) {
			LOG.error("TagFileStore.getResource() - parameter 'key' is null");
			throw new Exception("TagFileStore.getResources() - parameter 'key' is null.");
		}

		RangerService service = null;
		try {
			service = svcStore.getServiceByName(key.getServiceName());
		} catch(Exception excp) {
			LOG.error("TagFileStore.getResource - failed to get service " + key.getServiceName());
			throw new Exception("Invalid service: " + key.getServiceName());
		}

		RangerServiceDef serviceDef = null;

		try {
			serviceDef = svcStore.getServiceDefByName(service.getType());
		} catch (Exception exception) {
			LOG.error("TagFileStore.getResource - failed to get serviceDef for " + service.getType());
			throw new Exception("Invalid component-type: " + service.getType());
		}

		List<RangerTaggedResource> resources = null;

		if (MapUtils.isNotEmpty(key.getResourceSpec())) {

			TagServiceResources tagServiceResources = getResources(key.getServiceName(), 0L);
			resources = tagServiceResources.getTaggedResources();

			List<RangerTaggedResource> notMatchedResources = new ArrayList<>();

			if (CollectionUtils.isNotEmpty(resources)) {
				for (RangerTaggedResource resource : resources) {

					RangerDefaultPolicyResourceMatcher policyResourceMatcher =
							new RangerDefaultPolicyResourceMatcher();

					policyResourceMatcher.setPolicyResources(resource.getKey().getResourceSpec());

					policyResourceMatcher.setServiceDef(serviceDef);

					policyResourceMatcher.init();

					boolean isMatch = policyResourceMatcher.isExactMatch(key.getResourceSpec());

					if (! isMatch) {
						notMatchedResources.add(resource);
						break;
					}

				}

				resources.removeAll(notMatchedResources);
			}
		} else {
			resources = null;
		}

		RangerTaggedResource ret = (resources == null || CollectionUtils.isEmpty(resources)) ? null : resources.get(0);

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getResource( " + key.getServiceName() + " )" + ret);
		}

		return ret;
	}

	@Override
	public TagServiceResources getResources(String serviceName, Long lastTimeStamp) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getResources(" + serviceName + ", " + lastTimeStamp + ")");
		}
		List<RangerTaggedResource> taggedResources;

		SearchFilter filter = new SearchFilter();

		if (StringUtils.isNotBlank(serviceName)) {
			filter.setParam(SearchFilter.TAG_RESOURCE_SERVICE_NAME, serviceName);
		}

		if (lastTimeStamp != null) {
			filter.setParam(SearchFilter.TAG_RESOURCE_TIMESTAMP, Long.toString(lastTimeStamp.longValue()));
		}

		taggedResources = getResources(filter);

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getResources(" + serviceName + ", " + lastTimeStamp + ")");

		}

		TagServiceResources ret = new TagServiceResources();
		ret.setTaggedResources(taggedResources);
		// TBD
		ret.setLastUpdateTime(new Date());
		ret.setVersion(1L);

		return ret;

	}

	@Override
	public List<RangerTaggedResource> getResources(SearchFilter filter) throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getResources()");
		}

		List<RangerTaggedResource> ret = getAllTaggedResources();

		if (CollectionUtils.isNotEmpty(ret) && filter != null && !filter.isEmpty()) {
			CollectionUtils.filter(ret, predicateUtil.getPredicate(filter));

			//Comparator<RangerBaseModelObject> comparator = getSorter(filter);

			//if(comparator != null) {
			//Collections.sort(ret, comparator);
			//}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getResources(): count=" + (ret == null ? 0 : ret.size()));
		}

		return ret;
	}

	private List<RangerTagDef> getAllTagDefs() throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getAllTagDefs()");
		}

		List<RangerTagDef> ret = new ArrayList<RangerTagDef>();

		try {
			// load Tag definitions from file system
			List<RangerTagDef> sds = fileStoreUtil.loadFromDir(new Path(fileStoreUtil.getDataDir()), FILE_PREFIX_TAG_DEF, RangerTagDef.class);

			if (CollectionUtils.isNotEmpty(sds)) {
				for (RangerTagDef sd : sds) {
					if (sd != null) {
						// if the TagDef is already found, remove the earlier definition
						for (int i = 0; i < ret.size(); i++) {
							RangerTagDef currSd = ret.get(i);

							if (StringUtils.equals(currSd.getName(), sd.getName()) ||
									ObjectUtils.equals(currSd.getId(), sd.getId())) {
								ret.remove(i);
							}
						}

						ret.add(sd);
					}
				}
			}
			nextTagDefId = getMaxId(ret) + 1;
		} catch (Exception excp) {
			LOG.error("TagFileStore.getAllTagDefs(): failed to read Tag-defs", excp);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getAllTagDefs(): count=" + ret.size());
		}

		//Collections.sort(ret, idComparator);

		//for (RangerTagDef sd : ret) {
			//Collections.sort(sd.getResources(), resourceLevelComparator);
		//}

		return ret;
	}

	private List<RangerTaggedResource> getAllTaggedResources() throws Exception {
		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getAllTaggedResources()");
		}

		List<RangerTaggedResource> ret = new ArrayList<RangerTaggedResource>();

		try {
			// load resource definitions from file system
			List<RangerTaggedResource> resources = fileStoreUtil.loadFromDir(new Path(fileStoreUtil.getDataDir()), FILE_PREFIX_TAG_RESOURCE, RangerTaggedResource.class);

			if (CollectionUtils.isNotEmpty(resources)) {
				for (RangerTaggedResource resource : resources) {
					if (resource != null) {
						// if the RangerTaggedResource is already found, remove the earlier definition
						for (int i = 0; i < ret.size(); i++) {
							RangerTaggedResource currResource = ret.get(i);

							if (ObjectUtils.equals(currResource.getId(), resource.getId())) {
								ret.remove(i);
							}
						}

						ret.add(resource);
					}
				}
			}
			nextTagResourceId = getMaxId(ret) + 1;
		} catch (Exception excp) {
			LOG.error("TagFileStore.getAllTaggedResources(): failed to read tagged resources", excp);
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getAllTaggedResources(): count=" + ret.size());
		}


		//Collections.sort(ret, idComparator);

		//for (RangerTagDef sd : ret) {
			//Collections.sort(sd.getResources(), resourceLevelComparator);
		//}

		return ret;
	}

	@Override
	public List<String> getTags(String serviceName) throws Exception {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.getTags(" + serviceName + ")");
		}

		SortedSet<String> tagNameSet = new TreeSet<String>();

		TagServiceResources tagServiceResources = getResources(serviceName, 0L);
		List<RangerTaggedResource> resources = tagServiceResources.getTaggedResources();

		if (CollectionUtils.isNotEmpty(resources)) {
			for (RangerTaggedResource resource : resources) {
				List<RangerTaggedResource.RangerResourceTag> tags = resource.getTags();

				if (CollectionUtils.isNotEmpty(tags)) {
					for (RangerTaggedResource.RangerResourceTag tag : tags) {
						tagNameSet.add(tag.getName());
					}
				}
			}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.getTags(" + serviceName + ")");
		}

		return new ArrayList<String>(tagNameSet);
	}

	@Override
	public List<String> lookupTags(String serviceName, String tagNamePattern) throws Exception {

		if (LOG.isDebugEnabled()) {
			LOG.debug("==> TagFileStore.lookupTags(" + serviceName + ", " + tagNamePattern + ")");
		}

		List<String> tagNameList = getTags(serviceName);
		List<String> matchedTagList = new ArrayList<String>();

		if (CollectionUtils.isNotEmpty(tagNameList)) {
			Pattern p = Pattern.compile(tagNamePattern);
			for (String tagName : tagNameList) {
				Matcher m = p.matcher(tagName);
				if (LOG.isDebugEnabled()) {
					LOG.debug("TagFileStore.lookupTags) - Trying to match .... tagNamePattern=" + tagNamePattern + ", tagName=" + tagName);
				}
				if (m.matches()) {
					if (LOG.isDebugEnabled()) {
						LOG.debug("TagFileStore.lookupTags) - Match found.... tagNamePattern=" + tagNamePattern + ", tagName=" + tagName);
					}
					matchedTagList.add(tagName);
				}
			}
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug("<== TagFileStore.lookupTags(" + serviceName + ", " + tagNamePattern + ")");
		}

		return matchedTagList;
	}

	@Override
	public void deleteTagDefById(Long id) throws Exception {
		// TODO Auto-generated method stub

	}
}
