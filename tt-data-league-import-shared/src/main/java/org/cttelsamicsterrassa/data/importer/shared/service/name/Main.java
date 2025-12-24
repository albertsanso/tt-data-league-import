package org.cttelsamicsterrassa.data.importer.shared.service.name;

public class Main {
    public static void main(String[] args) {

        System.out.println(NameSimilarity.similarity(
                "oscar campos escala ctt els amics terrassa",
                "campos escala, oscar"
        ));

        System.out.println(NameSimilarity.similarity(
                "oscar campos escala",
                "campos escala, oscarr"
        ));

        System.out.println(NameSimilarity.similarity(
                "oscar campos escala",
                "campos escala, oskar"
        ));

        System.out.println(NameSimilarity.isSamePerson(
                "oscar campos escala",
                "campos escala, oscarr"
        ));
    }
}
