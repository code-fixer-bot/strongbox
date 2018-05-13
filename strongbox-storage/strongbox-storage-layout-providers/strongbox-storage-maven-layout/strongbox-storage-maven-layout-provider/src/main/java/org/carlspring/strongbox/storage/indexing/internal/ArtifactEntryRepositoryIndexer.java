package org.carlspring.strongbox.storage.indexing.internal;

import org.carlspring.strongbox.artifact.coordinates.MavenArtifactCoordinates;
import org.carlspring.strongbox.data.service.support.search.PagingCriteria;
import org.carlspring.strongbox.domain.ArtifactEntry;
import org.carlspring.strongbox.domain.RepositoryArtifactIdGroupEntry;
import org.carlspring.strongbox.providers.io.RepositoryPathResolver;
import org.carlspring.strongbox.services.RepositoryArtifactIdGroupService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;

import org.apache.maven.index.ArtifactContext;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author Przemyslaw Fusik
 */
@Component
public class ArtifactEntryRepositoryIndexer
{

    private static final int pageSize = 100;

    @Autowired
    private Indexer indexer;

    @Autowired
    private RepositoryArtifactIdGroupService repositoryArtifactIdGroupService;

    @Autowired
    private RepositoryPathResolver repositoryPathResolver;

    public IndexingContext generateIndexingContext(final String storageId,
                                                   final String repositoryId)
            throws IOException
    {

        IndexingContext indexingContext = createArtifactEntryIndexingContext(repositoryId);

        long totalArtifactGroupsInRepository = repositoryArtifactIdGroupService.count(storageId,
                                                                                      repositoryId);

        long iterations = totalArtifactGroupsInRepository / pageSize + 1;

        for (int i = 0; i < iterations; i++)
        {
            PagingCriteria pagingCriteria = new PagingCriteria(i * pageSize, pageSize);
            List<RepositoryArtifactIdGroupEntry> repositoryArtifactIdGroupEntries = repositoryArtifactIdGroupService.findMatching(
                    storageId,
                    repositoryId,
                    pagingCriteria);

            List<ArtifactContext> artifactContexts = createArtifactContexts(repositoryArtifactIdGroupEntries);
            indexer.addArtifactsToIndex(artifactContexts, indexingContext);
        }

        return indexingContext;
    }

    private List<ArtifactContext> createArtifactContexts(List<RepositoryArtifactIdGroupEntry> repositoryArtifactIdGroupEntries)
    {
        List<ArtifactContext> artifactContexts = new ArrayList<>();
        for (RepositoryArtifactIdGroupEntry repositoryArtifactIdGroupEntry : repositoryArtifactIdGroupEntries)
        {
            Map<String, List<ArtifactEntry>> groupedByVersion = groupArtifactEntriesByVersion(
                    repositoryArtifactIdGroupEntry);
            for (Map.Entry<String, List<ArtifactEntry>> sameVersionArtifactEntries : groupedByVersion.entrySet())
            {
                for (ArtifactEntry artifactEntry : sameVersionArtifactEntries.getValue())
                {
                    if (!isIndexable(artifactEntry))
                    {
                        continue;
                    }

                    List<ArtifactEntry> groupClone = new ArrayList<>(sameVersionArtifactEntries.getValue());
                    groupClone.remove(artifactEntry);

                    ArtifactEntryArtifactContextHelper artifactContextHelper = createArtifactContextHelper(
                            artifactEntry,
                            groupClone);
                    ArtifactEntryArtifactContext ac = new ArtifactEntryArtifactContext(artifactEntry,
                                                                                       artifactContextHelper);
                    artifactContexts.add(ac);
                }
            }
        }
        return artifactContexts;
    }

    private Map<String, List<ArtifactEntry>> groupArtifactEntriesByVersion(RepositoryArtifactIdGroupEntry groupEntry)
    {
        Map<String, List<ArtifactEntry>> groupedByVersion = new LinkedHashMap<>();
        for (ArtifactEntry artifactEntry : groupEntry.getArtifactEntries())
        {
            MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
            String version = coordinates.getVersion();
            List<ArtifactEntry> artifactEntries = groupedByVersion.get(version);
            if (artifactEntries == null)
            {
                artifactEntries = new ArrayList<>();
                groupedByVersion.put(version, artifactEntries);
            }
            artifactEntries.add(artifactEntry);
        }
        return groupedByVersion;
    }

    private ArtifactEntryArtifactContextHelper createArtifactContextHelper(ArtifactEntry artifactEntry,
                                                                           List<ArtifactEntry> group)
    {
        boolean pomExists = false;
        boolean sourcesExists = false;
        boolean javadocExists = false;
        if (group.size() < 1)
        {
            return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
        }
        MavenArtifactCoordinates coordinates = (MavenArtifactCoordinates) artifactEntry.getArtifactCoordinates();
        if ("javadoc" .equals(coordinates.getClassifier()) || "sources" .equals(coordinates.getClassifier()))
        {
            return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
        }
        if ("pom" .equals(coordinates.getExtension()))
        {
            return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
        }

        for (ArtifactEntry neighbour : group)
        {
            MavenArtifactCoordinates neighbourCoordinates = (MavenArtifactCoordinates) neighbour.getArtifactCoordinates();
            pomExists |=
                    ("pom" .equals(neighbourCoordinates.getExtension()) &&
                     neighbourCoordinates.getClassifier() == null);
            if (Objects.equals(coordinates.getExtension(), neighbourCoordinates.getExtension()))
            {
                javadocExists |= "javadoc" .equals(neighbourCoordinates.getClassifier());
                sourcesExists |= "sources" .equals(neighbourCoordinates.getClassifier());
            }
        }
        return new ArtifactEntryArtifactContextHelper(pomExists, sourcesExists, javadocExists);
    }

    /**
     * org.apache.maven.index.DefaultArtifactContextProducer#isIndexable(java.io.File)
     */
    private boolean isIndexable(final ArtifactEntry artifactEntry)
    {
        final String filename = Paths.get(artifactEntry.getArtifactPath()).getFileName().toString();

        if (filename.equals("maven-metadata.xml")
            // || filename.endsWith( "-javadoc.jar" )
            // || filename.endsWith( "-javadocs.jar" )
            // || filename.endsWith( "-sources.jar" )
            || filename.endsWith(".properties")
            // || filename.endsWith( ".xml" ) // NEXUS-3029
            || filename.endsWith(".asc") || filename.endsWith(".md5") || filename.endsWith(".sha1"))
        {
            return false;
        }

        return true;
    }

    private IndexingContext createArtifactEntryIndexingContext(final String repositoryId)
            throws IOException
    {

        // Files where local cache is (if any) and Lucene Index should be located
        final File centralLocalCache = new File(String.format("target/%s-cache", repositoryId));
        final File centralIndexDir = new File(String.format("target/%s-index", repositoryId));
        final String id = UUID.randomUUID().toString();
        return indexer.createIndexingContext(id, repositoryId, centralLocalCache, centralIndexDir, null, null, true,
                                             true, getIndexers());
    }

    private List<? extends IndexCreator> getIndexers()
    {
        return Arrays.asList(
                ArtifactEntryMinimalArtifactInfoIndexCreator.INSTANCE,
                ArtifactEntryJarFileContentsIndexCreator.INSTANCE);
    }


}
