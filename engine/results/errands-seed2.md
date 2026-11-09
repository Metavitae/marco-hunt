# Marco as a visible player, seed 2, 2000 games per row, 10x10, d6, walk up to the roll, one lie, limit 15 rounds (AI only)

### Sanity check: a signal with no information (always the same word), no Falcon, no Oracle help
| Rules | Median rounds | Solo minutes | Marco: errands | Marco: time | You win | Rival wins | Finds by accusing | Wrong accusations per game |
|---|---|---|---|---|---|---|---|---|
| Only accusing, signal says nothing | 10 | 7 | 44% | 36% | 12% | 9% | 100% | 0.0 |

### Short-range signal, errands 4, cost 2, landing still finds him, you reason
| Rules | Median rounds | Solo minutes | Marco: errands | Marco: time | You win | Rival wins | Finds by accusing | Wrong accusations per game |
|---|---|---|---|---|---|---|---|---|
| today: hot 4, warm 9 | 3 | 2 | 0% | 0% | 68% | 32% | 63% | 0.0 |
| hot 3, warm 5 | 3 | 2 | 0% | 0% | 60% | 40% | 61% | 0.0 |
| hot 2, warm 3 | 3 | 2 | 0% | 0% | 54% | 46% | 57% | 0.0 |
| hot 1, warm 3 | 3 | 2 | 0% | 0% | 54% | 46% | 57% | 0.0 |
| close 2, else nothing | 3 | 2 | 1% | 0% | 52% | 48% | 51% | 0.0 |

### Short-range signal, errands 4, cost 2, only accusing finds him, you reason
| Rules | Median rounds | Solo minutes | Marco: errands | Marco: time | You win | Rival wins | Finds by accusing | Wrong accusations per game |
|---|---|---|---|---|---|---|---|---|
| today: hot 4, warm 9 | 3 | 2 | 1% | 0% | 77% | 22% | 100% | 0.0 |
| hot 3, warm 5 | 3 | 2 | 0% | 0% | 69% | 30% | 100% | 0.0 |
| hot 2, warm 3 | 4 | 3 | 0% | 0% | 60% | 39% | 100% | 0.0 |
| hot 1, warm 3 | 4 | 3 | 1% | 0% | 61% | 39% | 100% | 0.0 |
| close 2, else nothing | 4 | 3 | 1% | 0% | 59% | 40% | 100% | 0.0 |

### Hot 2, warm 3, errand count, landing finds him
| Rules | Median rounds | Solo minutes | Marco: errands | Marco: time | You win | Rival wins | Finds by accusing | Wrong accusations per game |
|---|---|---|---|---|---|---|---|---|
| Errands 3 | 3 | 2 | 2% | 0% | 54% | 44% | 57% | 0.0 |
| Errands 5 | 3 | 2 | 0% | 0% | 54% | 46% | 58% | 0.0 |
| Errands 6 | 3 | 2 | 0% | 0% | 56% | 44% | 58% | 0.0 |

### Hot 2, warm 3, errands 4, landing finds him: other players
| Rules | Median rounds | Solo minutes | Marco: errands | Marco: time | You win | Rival wins | Finds by accusing | Wrong accusations per game |
|---|---|---|---|---|---|---|---|---|
| You just walk around | 4 | 3 | 2% | 0% | 2% | 96% | 49% | 0.0 |
| You and 5 rivals, you reason | 3 | 2 | 0% | 0% | 35% | 65% | 42% | 0.0 |
| You and 1 rival, you reason | 3 | 2 | 0% | 0% | 100% | 0% | 71% | 0.0 |
