package org.kloeckner.maven.plugin;

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.jdom.input.SAXBuilder;
import org.jdom.output.Format;
import org.jdom.output.XMLOutputter;
import org.kloeckner.maven.plugin.util.VersionRangeUtils;

/**
 * Goal updates the configured dependencies within the specified ranges.
 * 
 * @goal use-latest-versions
 * 
 * @phase process-sources
 */
public class VersionRange extends AbstractMojo {

	/** @parameter default-value="${project}" */
	private MavenProject mavenProject;

	/**
	 * The entry point to Aether, i.e. the component doing all the work.
	 * 
	 * @component
	 */
	private RepositorySystem repoSystem;

	/**
	 * The current repository/network configuration of Maven.
	 * 
	 * @parameter default-value="${repositorySystemSession}"
	 * @readonly
	 */
	private RepositorySystemSession repoSession;

	/**
	 * The project's remote repositories to use for the resolution of project
	 * dependencies.
	 * 
	 * @parameter default-value="${project.remoteProjectRepositories}"
	 * @readonly
	 */
	private List<RemoteRepository> remoteRepos;

	/**
	 * @parameter default-value="."
	 */
	private String dependencyVersionRangePath = ".";

	/**
	 * @parameter default-value="version-range-maven-plugin.properties"
	 */
	private String dependencyVersionRangeFile = "version-range-maven-plugin.properties";

	public void execute() throws MojoExecutionException {
		readWritePom(mavenProject);
	}

	private void readWritePom(MavenProject project)
			throws MojoExecutionException {

		Document document;
		String intro = null;
		String outtro = null;
		try {
			String content = VersionRangeUtils.readXmlFile(
					VersionRangeUtils.getStandardPom(project),
					VersionRangeUtils.LS);
			// we need to eliminate any extra whitespace inside elements, as
			// JDOM will nuke it
			content = content.replaceAll("<([^!][^>]*?)\\s{2,}([^>]*?)>",
					"<$1 $2>");
			content = content.replaceAll("(\\s{2,}|[^\\s])/>", "$1 />");

			SAXBuilder builder = new SAXBuilder();
			document = builder.build(new StringReader(content));

			// Normalize line endings to platform's style (XML processors like
			// JDOM normalize line endings to "\n" as
			// per section 2.11 of the XML spec)
			VersionRangeUtils.normaliseLineEndings(document);

			// rewrite DOM as a string to find differences, since text outside
			// the root element is not tracked
			StringWriter w = new StringWriter();
			Format format = Format.getRawFormat();
			format.setLineSeparator(VersionRangeUtils.LS);
			XMLOutputter out = new XMLOutputter(format);
			out.output(document.getRootElement(), w);

			int index = content.indexOf(w.toString());
			if (index >= 0) {
				intro = content.substring(0, index);
				outtro = content.substring(index + w.toString().length());
			} else {
				/*
				 * NOTE: Due to whitespace, attribute reordering or entity
				 * expansion the above indexOf test can easily fail. So let's
				 * try harder. Maybe some day, when JDOM offers a StaxBuilder
				 * and this builder employes
				 * XMLInputFactory2.P_REPORT_PROLOG_WHITESPACE, this whole mess
				 * can be avoided.
				 */
				final String SPACE = "\\s++";
				final String XML = "<\\?(?:(?:[^\"'>]++)|(?:\"[^\"]*+\")|(?:'[^\']*+'))*+>";
				final String INTSUB = "\\[(?:(?:[^\"'\\]]++)|(?:\"[^\"]*+\")|(?:'[^\']*+'))*+\\]";
				final String DOCTYPE = "<!DOCTYPE(?:(?:[^\"'\\[>]++)|(?:\"[^\"]*+\")|(?:'[^\']*+')|(?:"
						+ INTSUB + "))*+>";
				final String PI = XML;
				final String COMMENT = "<!--(?:[^-]|(?:-[^-]))*+-->";

				final String INTRO = "(?:(?:" + SPACE + ")|(?:" + XML + ")|(?:"
						+ DOCTYPE + ")|(?:" + COMMENT + ")|(?:" + PI + "))*";
				final String OUTRO = "(?:(?:" + SPACE + ")|(?:" + COMMENT
						+ ")|(?:" + PI + "))*";
				final String POM = "(?s)(" + INTRO + ")(.*?)(" + OUTRO + ")";

				Matcher matcher = Pattern.compile(POM).matcher(content);
				if (matcher.matches()) {
					intro = matcher.group(1);
					outtro = matcher.group(matcher.groupCount());
				}
			}
		} catch (JDOMException e) {
			throw new MojoExecutionException("Error reading POM: "
					+ e.getMessage(), e);
		} catch (IOException e) {
			throw new MojoExecutionException("Error reading POM: "
					+ e.getMessage(), e);
		}

		List<MavenProject> reactorProjects = new ArrayList<MavenProject>();
		Object result = new Object();
		transformDocument(project, document.getRootElement(), reactorProjects,
				result, false);

		// for overwriting:
		File pomFile = VersionRangeUtils.getStandardPom(project);
		// File pomFile = new File(project.getBasedir(), "newpom.xml");

		// if (simulate) {
		// File outputFile = new File(pomFile.getParentFile(), pomFile.getName()
		// + "." + pomSuffix);
		// writePom(outputFile, document, releaseDescriptor,
		// project.getModelVersion(), intro, outtro);
		// } else {

		VersionRangeUtils.writePom(pomFile, document,
				project.getModelVersion(), intro, outtro);
		// }

	}

	private void transformDocument(MavenProject project, Element rootElement,
			List<MavenProject> reactorProjects, Object result, boolean simulate)
			throws MojoExecutionException {
		Namespace namespace = rootElement.getNamespace();

		List<String> eagerArtifactsStrings = VersionRangeUtils
				.loadEagerDependencies(dependencyVersionRangePath,
						dependencyVersionRangeFile);

		Map<String, String> mappedVersions = getNextVersionMap(eagerArtifactsStrings);
		Map<String, String> originalVersions = getOriginalVersionMap(project);

		getLog().debug("mapped Versions (newer Versions):" + mappedVersions);
		getLog().debug("original Versions: " + originalVersions);

		Model model = project.getModel();
		Element properties = rootElement.getChild("properties", namespace);

		// String parentVersion = EagerUpdateUtils.rewriteParent(project,
		// rootElement, namespace, mappedVersions, originalVersions);
		VersionRangeUtils.rewriteParent(project, rootElement, namespace,
				mappedVersions, originalVersions);

		List<Element> roots = new ArrayList<Element>();
		roots.add(rootElement);
		List<Element> profileElements = VersionRangeUtils.getChildren(
				rootElement, "profiles", "profile");
		getLog().debug("got profiles: " + profileElements);
		roots.addAll(profileElements);

		for (Element root : roots) {
			rewriteArtifactVersions(
					VersionRangeUtils.getChildren(root, "parent"),
					mappedVersions, originalVersions, model, properties, result);
			rewriteArtifactVersions(VersionRangeUtils.getChildren(root,
					"dependencies", "dependency"), mappedVersions,
					originalVersions, model, properties, result);
			rewriteArtifactVersions(VersionRangeUtils.getChildren(root,
					"dependencyManagement", "dependencies", "dependency"),
					mappedVersions, originalVersions, model, properties, result);
		}
	}

	// private Map<String, String> getOriginalVersionMap(List<MavenProject>
	// reactorProjects) {
	private Map<String, String> getOriginalVersionMap(MavenProject projects) {
		HashMap<String, String> hashMap = new HashMap<String, String>();

		// TODO depmgmt, parent ...
		if (projects.getParentArtifact() != null) {
			hashMap.put(ArtifactUtils.versionlessKey(projects
					.getParentArtifact().getGroupId(), projects
					.getParentArtifact().getArtifactId()), projects
					.getParentArtifact().getArtifactId());
		}
		hashMap.putAll(buildVersionsMap(projects.getDependencies()));
		if (projects.getDependencyManagement() != null) {
			hashMap.putAll(buildVersionsMap(projects.getDependencyManagement()
					.getDependencies()));
		}

		for (Profile profile : projects.getActiveProfiles()) {
			hashMap.putAll(buildVersionsMap(profile.getDependencies()));
			if (profile.getDependencyManagement() != null) {
				hashMap.putAll(buildVersionsMap(profile
						.getDependencyManagement().getDependencies()));
			}
		}

		return hashMap;
	}

	private Map<String, String> buildVersionsMap(List<Dependency> dependencies) {
		Map<String, String> hashMap = new HashMap<String, String>();
		for (Dependency dep : dependencies) {
			String currentVersion = dep.getVersion();
			String versionlessKey = ArtifactUtils.versionlessKey(
					dep.getGroupId(), dep.getArtifactId());
			hashMap.put(versionlessKey, currentVersion);
		}
		return hashMap;
	}

	private Map<String, String> getNextVersionMap(List<String> eagerArtifacts)
			throws MojoExecutionException {
		HashMap<String, String> hashMap = new HashMap<String, String>();
		for (String s : eagerArtifacts) {
			DefaultArtifact artifact = new DefaultArtifact(s);
			Version newVersion = resolveNewVersion(artifact);
			String versionlessKey = ArtifactUtils.versionlessKey(
					artifact.getGroupId(), artifact.getArtifactId());
			hashMap.put(versionlessKey, newVersion.toString());
		}
		return hashMap;
	}

	private Version resolveNewVersion(Artifact artifact)
			throws MojoExecutionException {
		VersionRangeRequest request = new VersionRangeRequest();
		request.setArtifact(artifact);
		request.setRepositories(remoteRepos);

		getLog().debug(
				"Resolving artifact " + artifact + " from " + remoteRepos);

		VersionRangeResult rangeResult;
		try {
			rangeResult = repoSystem.resolveVersionRange(repoSession, request);
		} catch (VersionRangeResolutionException e) {
			e.printStackTrace();
			throw new MojoExecutionException("unable to resolve versions for: "
					+ artifact, e);
		}
		getLog().debug(
				"artifactId: " + artifact.getArtifactId() + " - "
						+ rangeResult.getVersions());
		Version newestVersion = rangeResult.getHighestVersion();
		return newestVersion;
	}

	private void rewriteArtifactVersions(Collection<Element> elements,
			Map<String, String> mappedVersions,
			Map<String, String> originalVersions, Model projectModel,
			Element properties, Object result) throws MojoExecutionException {
		if (elements == null) {
			return;
		}

		String projectId = ArtifactUtils.versionlessKey(
				projectModel.getGroupId(), projectModel.getArtifactId());
		for (Element element : elements) {
			Element versionElement = element.getChild("version",
					element.getNamespace());
			if (versionElement == null) {
				// managed dependency or unversioned plugin
				continue;
			}
			String rawVersion = versionElement.getTextTrim();
			Element groupIdElement = element.getChild("groupId",
					element.getNamespace());
			if (groupIdElement == null) {
				if ("plugin".equals(element.getName())) {
					groupIdElement = new Element("groupId",
							element.getNamespace());
					groupIdElement.setText("org.apache.maven.plugins");
				} else {
					// incomplete dependency
					continue;
				}
			}
			String groupId = VersionRangeUtils.interpolate(
					groupIdElement.getTextTrim(), projectModel);

			Element artifactIdElement = element.getChild("artifactId",
					element.getNamespace());
			if (artifactIdElement == null) {
				// incomplete element
				continue;
			}
			String artifactId = VersionRangeUtils.interpolate(
					artifactIdElement.getTextTrim(), projectModel);

			String key = ArtifactUtils.versionlessKey(groupId, artifactId);
			String mappedVersion = mappedVersions.get(key);
			String originalVersion = originalVersions.get(key);

			if (mappedVersion != null) {
				if (rawVersion.equals(originalVersion)) {
					getLog().info(
							"Updating " + artifactId + " to " + mappedVersion);
					VersionRangeUtils.rewriteValue(versionElement,
							mappedVersion);
				} else if (rawVersion.matches("\\$\\{.+\\}")) {
					String expression = rawVersion.substring(2,
							rawVersion.length() - 1);

					if (expression.startsWith("project.")
							|| expression.startsWith("pom.")
							|| "version".equals(expression)) {
						if (!mappedVersion
								.equals(mappedVersions.get(projectId))) {
							getLog().info(
									"Updating " + artifactId + " to "
											+ mappedVersion);
							VersionRangeUtils.rewriteValue(versionElement,
									mappedVersion);
						} else {
							getLog().info(
									"Ignoring artifact version update for expression "
											+ rawVersion);
						}
					} else if (properties != null) {
						// version is an expression, check for properties to
						// update instead
						Element property = properties.getChild(expression,
								properties.getNamespace());
						if (property != null) {
							String propertyValue = property.getTextTrim();

							if (propertyValue.equals(originalVersion)) {
								getLog().info(
										"Updating " + rawVersion + " to "
												+ mappedVersion);
								// change the property only if the property is
								// the same as what's in the reactor
								VersionRangeUtils.rewriteValue(property,
										mappedVersion);
							} else if (mappedVersion.equals(propertyValue)) {
								// this property may have been updated during
								// processing a sibling.
								getLog().info(
										"Ignoring artifact version update for expression "
												+ rawVersion
												+ " because it is already updated");
							} else if (!mappedVersion.equals(rawVersion)) {
								if (mappedVersion.matches("\\$\\{project.+\\}")
										|| mappedVersion
												.matches("\\$\\{pom.+\\}")
										|| "${version}".equals(mappedVersion)) {
									getLog().info(
											"Ignoring artifact version update for expression "
													+ mappedVersion);
									// ignore... we cannot update this
									// expression
								} else {
									// the value of the expression conflicts
									// with what the user wanted to release
									throw new MojoExecutionException(
											"The artifact (" + key
													+ ") requires a "
													+ "different version ("
													+ mappedVersion
													+ ") than what is found ("
													+ propertyValue
													+ ") for the expression ("
													+ expression + ") in the "
													+ "project (" + projectId
													+ ").");
								}
							}
						} else {
							// the expression used to define the version of this
							// artifact may be inherited
							// TODO needs a better error message, what pom? what
							// dependency?
							throw new MojoExecutionException(
									"The version could not be updated: "
											+ rawVersion);
						}
					}
				} else {
					// different/previous version not related to current release
					getLog().debug(
							"different/previous version not related to current release");
				}
			} else {
				// artifact not related to current release
				getLog().debug("artifact not related to current release");
			}
		}
	}
}