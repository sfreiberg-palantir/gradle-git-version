package com.palantir.gradle.gitversion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JGit implementation of git describe with required flags. JGit support for describe is minimal and there is no support
 * for --first-parent behavior.
 */
class JGitDescribe implements GitDescribe {
    private static final Logger log = LoggerFactory.getLogger(JGitDescribe.class);
    private final Git git;
    private final ReleasingModel model;

    JGitDescribe(Git git, ReleasingModel model) {
        this.git = git;
        this.model = model;
    }

    @Override
    public String describe(String prefix) {
        try {
            ObjectId headObjectId = git.getRepository().resolve(Constants.HEAD);

            List<String> revs = revList(headObjectId);

            Map<String, RefWithTagName> commitHashToTag = mapCommitsToTags(git);

            // Walk back commit ancestors looking for tagged one
            for (int depth = 0; depth < revs.size(); depth++) {
                String rev = revs.get(depth);
                if (commitHashToTag.containsKey(rev)) {
                    String exactTag = commitHashToTag.get(rev).getTag();
                    // Mimics '--match=${prefix}*' flag in 'git describe --tags --exact-match'
                    if (exactTag.startsWith(prefix)) {
                        String longDescription = longDescription(revs, depth, exactTag);
                        if (depth == 0) {
                            if (model == ReleasingModel.RELEASE_BRANCH && exactTag.endsWith(".0")) {
                                return longDescription;
                            } else {
                                return exactTag;
                            }
                        } else {
                            return longDescription;
                        }
                    }
                }
            }

            // No tags found, so return commit hash of HEAD
            return GitUtils.abbrevHash(headObjectId.getName());
        } catch (Exception e) {
            log.debug("JGit describe failed with {}", e);
            return null;
        }
    }

    private String longDescription(List<String> revs, int depth, String exactTag) {
        return String.format("%s-%s-g%s", exactTag, depth, GitUtils.abbrevHash(revs.get(0)));
    }

    // Mimics 'git rev-list --first-parent <commit>'
    private List<String> revList(ObjectId initialObjectId) throws IOException {
        ArrayList<String> revs = new ArrayList<>();

        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo)) {
            walk.setRetainBody(false);
            RevCommit head = walk.parseCommit(initialObjectId);

            while (true) {
                revs.add(head.getName());

                RevCommit[] parents = head.getParents();
                if (parents == null || parents.length == 0) {
                    break;
                }

                head = walk.parseCommit(parents[0]);
            }
        }

        return revs;
    }

    // Maps all commits returned by 'git show-ref --tags -d' to output of 'git describe --tags --exact-match <commit>'
    private Map<String, RefWithTagName> mapCommitsToTags(Git git) {
        RefWithTagNameComparator comparator = new RefWithTagNameComparator(git);

        // Maps commit hash to list of all refs pointing to given commit hash.
        // All keys in this map should be same as commit hashes in 'git show-ref --tags -d'
        Map<String, RefWithTagName> commitHashToTag = new HashMap<>();
        Repository repository = git.getRepository();
        for (Map.Entry<String, Ref> entry : repository.getTags().entrySet()) {
            Ref peeledRef = repository.peel(entry.getValue());
            RefWithTagName refWithTagName = new RefWithTagName(peeledRef, entry.getKey());

            // Peel ref object
            ObjectId peeledObjectId = peeledRef.getPeeledObjectId();
            if (peeledObjectId == null) {
                // Lightweight tag (commit object)
                updateCommitHashMap(commitHashToTag, comparator, peeledRef.getObjectId(), refWithTagName);
            } else {
                // Annotated tag (tag object)
                updateCommitHashMap(commitHashToTag, comparator, peeledObjectId, refWithTagName);
            }
        }
        return commitHashToTag;
    }

    private void updateCommitHashMap(Map<String, RefWithTagName> map, RefWithTagNameComparator comparator,
                                       ObjectId objectId, RefWithTagName ref) {
        // Smallest ref (ordered by this comparator) from list of refs is chosen for each commit.
        // This ensures we get same behavior as in 'git describe --tags --exact-match <commit>'
        String commitHash = objectId.getName();
        if (map.containsKey(commitHash)) {
            if (comparator.compare(ref, map.get(commitHash)) < 0) {
                map.put(commitHash, ref);
            }
        } else {
            map.put(commitHash, ref);
        }
    }
}
