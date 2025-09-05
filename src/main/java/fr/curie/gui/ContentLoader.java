package fr.curie.gui;

import com.vladsch.flexmark.ext.attributes.AttributesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.LinkResolver;
import com.vladsch.flexmark.html.LinkResolverFactory;
import com.vladsch.flexmark.html.renderer.LinkResolverBasicContext;
import com.vladsch.flexmark.html.renderer.LinkStatus;
import com.vladsch.flexmark.html.renderer.ResolvedLink;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;


public class ContentLoader {
    private static final Parser parser;
    private static final HtmlRenderer renderer;

    static {
        // Create a custom LinkResolverFactory that handles resource paths
        class ResourceLinkResolver implements LinkResolver {

            @Override
            public @NotNull ResolvedLink resolveLink(@NotNull Node node, @NotNull LinkResolverBasicContext linkResolverContext, @NotNull ResolvedLink link) {
                // We only want to resolve relative paths for images/links
                if (link.getUrl().startsWith("http://") || link.getUrl().startsWith("https://")) {
                    return link;
                }

                // Get the URL for the resource from the classpath
                String resourcePath = link.getUrl().startsWith("/") ? link.getUrl() : "/" + link.getUrl();
                URL resourceUrl = ContentLoader.class.getResource(resourcePath);

                if (resourceUrl != null) {
                    return link.withStatus(LinkStatus.VALID)
                            .withUrl(resourceUrl.toExternalForm());
                } else {
                    System.err.println("Could not resolve resource link: " + resourcePath);
                    return link.withStatus(LinkStatus.INVALID);
                }
            }
        }

        // The LinkResolver needs to be created by a factory
        class ResourceLinkResolverFactory implements LinkResolverFactory {
            @Override
            public @Nullable Set<Class<?>> getAfterDependents() {
                return null;
            }

            @Override
            public @Nullable Set<Class<?>> getBeforeDependents() {
                return null;
            }

            @Override
            public boolean affectsGlobalScope() { return false; }

            @Override
            public @NotNull LinkResolver apply(@NotNull LinkResolverBasicContext linkResolverBasicContext) {
                return new ResourceLinkResolver();
            }

        }

        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(AttributesExtension.create()));


        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options)
                .linkResolverFactory(new ResourceLinkResolverFactory())
                .build();
    }

    /**
     * Reads a resource file, converts it from Markdown to HTML with resolved image paths.
     */
    public static String loadAndParseMarkdown(String resourcePath) {
        // The leading slash is important for getResourceAsStream
        String fullPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;

        try (InputStream is = ContentLoader.class.getResourceAsStream(fullPath)) {
            if (is == null) {
                System.err.println("Resource not found: " + fullPath);
                return "<html><body><b>Error:</b> Content file not found at " + resourcePath + "</body></html>";
            }

            String markdownContent = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));

            Node document = parser.parse(markdownContent);
            return renderer.render(document);

        } catch (Exception e) {
            System.err.println("Error reading or parsing resource: " + fullPath);
            e.printStackTrace();
            return "<html><body><b>Error:</b> Could not load content. See logs for details.</body></html>";
        }
    }


}
