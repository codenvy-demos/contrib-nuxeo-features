package org.nuxeo.ecm.platform.publisher.impl.finder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.Filter;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.LifeCycleConstants;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.UnrestrictedSessionRunner;
import org.nuxeo.ecm.core.api.impl.CompoundFilter;
import org.nuxeo.ecm.core.api.impl.DocumentModelListImpl;
import org.nuxeo.ecm.core.api.impl.FacetFilter;
import org.nuxeo.ecm.core.api.impl.LifeCycleFilter;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.schema.FacetNames;
import org.nuxeo.ecm.core.schema.SchemaManager;
import org.nuxeo.ecm.platform.publisher.helper.RootSectionFinder;
import org.nuxeo.runtime.api.Framework;

public abstract class AbstractRootSectionsFinder extends
        UnrestrictedSessionRunner implements RootSectionFinder {

    public static final String SCHEMA_PUBLISHING = "publishing";

    public static final String SECTIONS_PROPERTY_NAME = "publish:sections";

    protected static Set<String> sectionRootTypes;

    protected static Set<String> sectionTypes;

    protected CoreSession userSession;

    protected List<String> unrestrictedSectionRootFromWorkspaceConfig;

    protected List<String> unrestrictedDefaultSectionRoot;

    protected DocumentModelList accessibleSectionRoots;

    protected DocumentModel currentDocument;

    protected static final Log log = LogFactory.getLog(AbstractRootSectionsFinder.class);

    protected abstract void computeUserSectionRoots(DocumentModel currentDoc)
            throws ClientException;

    protected abstract String buildQuery(String path);

    protected abstract void computeUnrestrictedRoots(CoreSession session)
            throws ClientException;

    public AbstractRootSectionsFinder(CoreSession userSession) {
        super(userSession);
        this.userSession = userSession;
    }

    @Override
    public void reset() {
        this.currentDocument = null;
    }

    @Override
    public DocumentModelList getAccessibleSectionRoots(DocumentModel currentDoc)
            throws ClientException {
        if ((currentDocument == null)
                || (!currentDocument.getRef().equals(currentDoc.getRef()))) {
            computeUserSectionRoots(currentDoc);
        }
        return accessibleSectionRoots;
    }

    @Override
    public DocumentModelList getSectionRootsForWorkspace(
            DocumentModel currentDoc, boolean addDefaultSectionRoots)
            throws ClientException {
        if ((currentDocument == null)
                || (!currentDocument.getRef().equals(currentDoc.getRef()))) {
            computeUserSectionRoots(currentDoc);
        }

        if (unrestrictedDefaultSectionRoot.isEmpty() && addDefaultSectionRoots) {
            if (unrestrictedDefaultSectionRoot == null
                    || unrestrictedDefaultSectionRoot.isEmpty()) {
                DocumentModelList defaultSectionRoots = getDefaultSectionRoots(session);
                unrestrictedDefaultSectionRoot = new ArrayList<String>();
                for (DocumentModel root : defaultSectionRoots) {
                    unrestrictedDefaultSectionRoot.add(root.getPathAsString());
                }
            }
        }

        return getFiltredSectionRoots(
                unrestrictedSectionRootFromWorkspaceConfig, true);
    }

    @Override
    public DocumentModelList getSectionRootsForWorkspace(
            DocumentModel currentDoc) throws ClientException {
        return getSectionRootsForWorkspace(currentDoc, false);
    }

    @Override
    public DocumentModelList getDefaultSectionRoots(boolean onlyHeads,
            boolean addDefaultSectionRoots) throws ClientException {
        if (unrestrictedDefaultSectionRoot == null) {
            computeUserSectionRoots(null);
        }

        if (unrestrictedDefaultSectionRoot.isEmpty() && addDefaultSectionRoots) {
            if (unrestrictedDefaultSectionRoot == null
                    || unrestrictedDefaultSectionRoot.isEmpty()) {
                DocumentModelList defaultSectionRoots = getDefaultSectionRoots(session);
                unrestrictedDefaultSectionRoot = new ArrayList<String>();
                for (DocumentModel root : defaultSectionRoots) {
                    unrestrictedDefaultSectionRoot.add(root.getPathAsString());
                }
            }
        }

        return getFiltredSectionRoots(unrestrictedDefaultSectionRoot, onlyHeads);
    }

    @Override
    public DocumentModelList getDefaultSectionRoots(boolean onlyHeads)
            throws ClientException {
        return getDefaultSectionRoots(onlyHeads, false);
    }

    protected DocumentModelList getFiltredSectionRoots(List<String> rootPaths,
            boolean onlyHeads) throws ClientException {
        List<DocumentRef> filtredDocRef = new ArrayList<DocumentRef>();
        List<DocumentRef> trashedDocRef = new ArrayList<DocumentRef>();

        for (String rootPath : rootPaths) {
            DocumentRef rootRef = new PathRef(rootPath);
            if (userSession.hasPermission(rootRef, SecurityConstants.READ)) {
                filtredDocRef.add(rootRef);
            } else {
                DocumentModelList accessibleSections = userSession.query(buildQuery(rootPath));
                for (DocumentModel section : accessibleSections) {
                    if (onlyHeads
                            && ((filtredDocRef.contains(section.getParentRef())) || (trashedDocRef.contains(section.getParentRef())))) {
                        trashedDocRef.add(section.getRef());
                    } else {
                        filtredDocRef.add(section.getRef());
                    }
                }
            }
        }
        DocumentModelList documents = userSession.getDocuments(filtredDocRef.toArray(new DocumentRef[filtredDocRef.size()]));
        return filterDocuments(documents);
    }

    protected DocumentModelList filterDocuments(DocumentModelList docs) {
        DocumentModelList filteredDocuments = new DocumentModelListImpl();
        FacetFilter facetFilter = new FacetFilter(
                Arrays.asList(FacetNames.FOLDERISH),
                Arrays.asList(FacetNames.HIDDEN_IN_NAVIGATION));
        LifeCycleFilter lfFilter = new LifeCycleFilter(
                LifeCycleConstants.DELETED_STATE, false);
        Filter filter = new CompoundFilter(facetFilter, lfFilter);
        for (DocumentModel doc : docs) {
            if (filter.accept(doc)) {
                filteredDocuments.add(doc);
            }
        }
        return filteredDocuments;
    }

    protected DocumentModelList getDefaultSectionRoots(CoreSession session)
            throws ClientException {
        // XXX replace by a query !!!
        DocumentModelList sectionRoots = new DocumentModelListImpl();
        DocumentModelList domains = session.getChildren(
                session.getRootDocument().getRef(), "Domain");
        for (DocumentModel domain : domains) {
            for (String sectionRootNameType : getSectionRootTypes()) {
                DocumentModelList children = session.getChildren(
                        domain.getRef(), sectionRootNameType);
                sectionRoots.addAll(children);
            }
        }
        return sectionRoots;
    }

    protected DocumentModelList getSectionRootsFromWorkspaceConfig(
            DocumentModel workspace, CoreSession session)
            throws ClientException {

        DocumentModelList selectedSections = new DocumentModelListImpl();

        if (workspace.hasSchema(SCHEMA_PUBLISHING)) {
            String[] sectionIdsArray = (String[]) workspace.getPropertyValue(SECTIONS_PROPERTY_NAME);

            List<String> sectionIdsList = new ArrayList<String>();

            if (sectionIdsArray != null && sectionIdsArray.length > 0) {
                sectionIdsList = Arrays.asList(sectionIdsArray);
            }

            if (sectionIdsList != null) {
                for (String currentSectionId : sectionIdsList) {
                    try {
                        DocumentModel sectionToAdd = session.getDocument(new IdRef(
                                currentSectionId));
                        selectedSections.add(sectionToAdd);
                    } catch (ClientException e) {
                        log.warn("Section with ID=" + currentSectionId
                                + " not found for document with ID="
                                + workspace.getId());
                    }
                }
            }
        }
        return selectedSections;
    }

    @Override
    public void run() throws ClientException {
        computeUnrestrictedRoots(session);
    }

    protected Set<String> getSectionRootTypes() {
        if (sectionRootTypes == null) {
            sectionRootTypes = getTypeNamesForFacet(FacetNames.MASTER_PUBLISH_SPACE);
            if (sectionRootTypes == null) {
                sectionRootTypes = new HashSet<String>();
            }
        }
        return sectionRootTypes;
    }

    protected Set<String> getTypeNamesForFacet(String facetName) {
        SchemaManager schemaManager = Framework.getLocalService(SchemaManager.class);
        Set<String> publishRoots = schemaManager.getDocumentTypeNamesForFacet(facetName);
        if (publishRoots == null || publishRoots.isEmpty()) {
            return null;
        }
        return publishRoots;
    }

    protected Set<String> getSectionTypes() {
        if (sectionTypes == null) {
            sectionTypes = getTypeNamesForFacet(FacetNames.MASTER_PUBLISH_SPACE);
            if (sectionTypes == null) {
                sectionTypes = new HashSet<String>();
            }
        }
        return sectionTypes;
    }

}