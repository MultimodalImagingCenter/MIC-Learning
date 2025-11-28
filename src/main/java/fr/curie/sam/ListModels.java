package fr.curie.sam;

import ai.djl.repository.zoo.ModelLoader;
import ai.djl.repository.zoo.ModelZoo;

import java.util.Collection;

public class ListModels {

    public static void main(String[] args) {
        System.out.println("Recherche des modèles PyTorch...");

        String groupId = "ai.djl.pytorch";

        try {
            // 1. Récupération du Zoo spécifique
            ModelZoo zoo = ModelZoo.getModelZoo(groupId);

            if (zoo == null) {
                System.err.println("Erreur : Le ModelZoo '" + groupId + "' est introuvable.");
                System.err.println("Avez-vous ajouté la dépendance 'ai.djl.pytorch:pytorch-model-zoo' ?");
                return;
            }

            // 2. Récupération de la liste des loaders de modèles
            Collection<ModelLoader> loaders = zoo.getModelLoaders();

            System.out.println("Modèles trouvés : " + loaders.size());

            // 3. Boucle d'affichage
            for (ModelLoader loader : loaders) {
                String artifactId = loader.getArtifactId();

                // On reconstruit l'URL pour référence
                String url = "djl://" + groupId + "/" + artifactId;

                System.out.println("--------------------------------------------------");
                System.out.println("Nom (ArtifactId) : " + artifactId);
                System.out.println("URL d'utilisation : " + url);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}