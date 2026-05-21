# Prompt : Ajout de @TypeKeyConfig à la bibliothèque type-index

## Contexte

Tu travailles sur la bibliothèque Java `type-index` (https://github.com/cyfko/type-index).

C'est un système de registre de types compile-time qui mappe des clés logiques stables vers des classes Java via un annotation processor. Aujourd'hui, la seule façon d'enregistrer un type est d'annoter **directement** la classe cible avec `@TypeKey("ma-cle")`. Le processor génère ensuite un `RegistryProviderImpl` contenant un `Map<String, Class<?>>` immuable.

**Problème** : annoter directement les classes domaine couple le modèle métier à l'infrastructure de sérialisation. Dans une architecture hexagonale/DDD, les classes domaine (records, sealed interfaces, enums) ne doivent porter aucune annotation d'infrastructure.

## Objectif

Ajouter un mécanisme **déclaratif et externe** permettant d'associer des clés `@TypeKey` à des types **sans les annoter directement**. Le mécanisme existant (`@TypeKey` directement sur la classe) doit continuer à fonctionner — la nouvelle feature est complémentaire, pas un remplacement.

## Design retenu

### Nouvelle annotation `@TypeKeyConfig`

Appliquée sur une **interface** (jamais instanciée). Chaque **méthode** de cette interface :
- est annotée avec `@TypeKey("ma-cle")`
- a un **type de retour** qui est la classe cible à enregistrer
- n'a **aucun paramètre**
- le nom de la méthode est libre et n'a aucune signification fonctionnelle

```java
@TypeKeyConfig
public interface AuthTypeKeys {

    @TypeKey("auth.steps")
    AuthSteps steps();

    @TypeKey("auth.signal.otp-sent")
    AuthSignal.OtpSent otpSent();

    @TypeKey("auth.signal.otp-validated")
    AuthSignal.OtpValidated otpValidated();
}
```

### Ce que le processor doit faire

1. Scanner les interfaces annotées `@TypeKeyConfig` en plus des classes annotées `@TypeKey`.
2. Pour chaque méthode annotée `@TypeKey` dans une interface `@TypeKeyConfig` :
   - Extraire la valeur de `@TypeKey` → c'est la clé.
   - Extraire le type de retour de la méthode → c'est la classe cible.
   - Valider que le type de retour est une classe, un record, ou un enum (pas void, pas un primitif, pas un type générique paramétré).
   - Valider que la méthode n'a aucun paramètre.
3. Fusionner les entrées de `@TypeKeyConfig` avec les entrées `@TypeKey` directes dans le même `RegistryProviderImpl` généré.
4. Appliquer les mêmes validations que pour `@TypeKey` direct :
   - Unicité des clés (y compris entre les deux mécanismes — une clé ne peut pas être déclarée deux fois).
   - Caractères autorisés dans la clé.
   - Erreur de compilation si une violation est détectée.

### Fichiers à créer ou modifier

**Nouveau fichier :**
- `src/main/java/io/github/cyfko/typeindex/TypeKeyConfig.java` — l'annotation

```java
package io.github.cyfko.typeindex;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an external type key configuration.
 *
 * Each method in this interface annotated with {@link TypeKey} declares
 * a mapping from a stable key to the method's return type, without
 * requiring the target type to carry any annotation.
 *
 * The interface is never instantiated — it serves purely as a
 * compile-time declaration for the annotation processor.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface TypeKeyConfig {
}
```

**Fichier à modifier :**
- `TypeIndexProcessor.java` — le processor existant, pour ajouter le scan des interfaces `@TypeKeyConfig`

### Contraintes

- L'annotation `@TypeKeyConfig` est `@Retention(SOURCE)` — comme `@TypeKey`.
- `@TypeKeyConfig` ne peut être appliquée que sur des **interfaces** (pas des classes, pas des enums, pas des records). Le processor doit émettre une erreur si c'est appliqué ailleurs.
- Les méthodes dans l'interface `@TypeKeyConfig` qui ne sont **pas** annotées `@TypeKey` sont ignorées silencieusement.
- Les méthodes annotées `@TypeKey` dans l'interface **doivent** avoir zéro paramètre. Si une méthode a des paramètres, le processor émet une erreur de compilation.
- Le type de retour **doit** être une classe, un record, ou un enum concret. Si c'est `void`, un primitif, ou un type générique paramétré (ex: `List<String>`), le processor émet une erreur.
- Un même type peut avoir au plus une clé — si `OtpSent` est enregistré via `@TypeKeyConfig` ET via `@TypeKey` direct sur la classe, c'est une erreur de compilation (conflit).
- Un même type peut apparaître dans plusieurs interfaces `@TypeKeyConfig` à condition que la clé soit identique dans toutes les occurrences. Des clés différentes pour le même type sont une erreur.

### Tests à écrire

1. **Happy path** : interface `@TypeKeyConfig` avec 3 méthodes annotées → les 3 entrées sont dans le registre généré.
2. **Coexistence** : `@TypeKeyConfig` + `@TypeKey` direct sur d'autres classes → toutes les entrées sont fusionnées dans le même registre.
3. **Conflit de clé** : même clé dans `@TypeKeyConfig` et `@TypeKey` direct sur une classe différente → erreur de compilation.
4. **Conflit de type** : même type enregistré avec deux clés différentes dans deux `@TypeKeyConfig` → erreur de compilation.
5. **Méthode avec paramètres** → erreur de compilation.
6. **Type de retour void** → erreur de compilation.
7. **Type de retour primitif** → erreur de compilation.
8. **`@TypeKeyConfig` sur une classe (pas une interface)** → erreur de compilation.
9. **Méthode non annotée `@TypeKey` dans l'interface** → ignorée, pas d'erreur.
10. **Reverse lookup `keyOf()`** : `TypeKeyRegistry.keyOf(OtpSent.class)` retourne la clé déclarée dans `@TypeKeyConfig`.
11. **Forward lookup `resolve()`** : `TypeKeyRegistry.resolve("auth.signal.otp-sent")` retourne `OtpSent.class`.

### Ce qui ne change PAS

- L'API publique de `TypeKeyRegistry` (resolve, keyOf, canResolve, getRegistryProvider).
- Le format du `RegistryProviderImpl` généré.
- Le mécanisme `@TypeKey` direct sur les classes — il reste fonctionnel et prioritaire.
- La résolution multi-tier (registre → primitifs → classpath).
- La thread safety du registre.

## Résumé

L'objectif est simple : permettre d'associer une clé stable à un type **sans toucher au type lui-même**, via une interface de configuration externe scannée au compile-time. Le résultat final dans le registre est indistinguable d'un `@TypeKey` direct — `resolve()` et `keyOf()` fonctionnent de manière identique dans les deux cas.
