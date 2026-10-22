import { BackendNotice } from '../components/BackendNotice';
import { useCatalog } from '../backend/CatalogProvider';
import React, { useState } from 'react';
import { Text, View } from 'react-native';
import { router } from 'expo-router';
import { useDemo } from '../store';
import { categories, defaultFilters, money, searchTalents, SortOrder } from '../talent';
import { c, f } from '../theme';
import {
  CategoryChip,
  Chips,
  ChoiceModal,
  EmptyState,
  FilterChip,
  LoadingSkeleton,
  SampleNote,
  Screen,
  SearchBar,
  TalentGrid,
} from '../components/ui';
export default function Search() {
  const catalog = useCatalog();
  const store = useDemo();
  const { query, setQuery, category, setCategory, filters, setFilters, sort, setSort } = store;
  const [sortOpen, setSortOpen] = useState(false);
  const results = searchTalents(query, category, filters, sort, catalog.talents);
  const header = (
    <View style={{ gap: 12, marginBottom: 16 }}>
      <BackendNotice />
      <SearchBar value={query} onChange={setQuery} onFilters={() => router.push('/filters')} />
      <Chips>
        {categories.map((cat) => (
          <CategoryChip
            key={cat}
            title={cat === 'All talent' ? 'All' : cat}
            active={cat === category}
            onPress={() => setCategory(cat)}
          />
        ))}
      </Chips>
      <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: 6 }}>
        {filters.city !== 'All cities' && (
          <FilterChip
            title={filters.city}
            onRemove={() => setFilters({ ...filters, city: 'All cities' })}
          />
        )}
        {((filters.minRate ?? 0) > 0 || filters.maxRate < defaultFilters.maxRate) && (
          <FilterChip
            title={`${money(filters.minRate ?? 0)}–${money(filters.maxRate)}`}
            onRemove={() => setFilters({ ...filters, minRate: 0, maxRate: defaultFilters.maxRate })}
          />
        )}
        {filters.availableOnly && (
          <FilterChip
            title="Available"
            onRemove={() => setFilters({ ...filters, availableOnly: false })}
          />
        )}
        {filters.skills?.map((skill) => (
          <FilterChip
            key={skill}
            title={skill}
            onRemove={() =>
              setFilters({ ...filters, skills: filters.skills?.filter((s) => s !== skill) })
            }
          />
        ))}
      </View>
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
        <Text style={{ color: c.text, fontSize: 12, fontFamily: f.semibold }}>
          {results.length} {results.length === 1 ? 'talent' : 'talents'} found
        </Text>
        <CategoryChip
          title={`Sort: ${sort === 'Recommended' ? 'Recommended' : sort === 'Name' ? 'Name' : sort === 'Price: low to high' ? 'Price ↑' : 'Price ↓'}`}
          active={false}
          onPress={() => setSortOpen(true)}
        />
      </View>
      <SampleNote
        text={
          catalog.mock
            ? 'Sample catalog · indicative daily rates'
            : 'Published talent · indicative daily rates'
        }
      />
    </View>
  );
  if (!store.hydrated)
    return (
      <Screen title="Search talent">
        <LoadingSkeleton />
      </Screen>
    );
  return (
    <Screen title="Search talent" scroll={false}>
      <TalentGrid
        data={results}
        header={header}
        empty={
          catalog.loading || catalog.error ? (
            <View />
          ) : !store.hydrated ? (
            <LoadingSkeleton />
          ) : (
            <EmptyState
              title="No matches just yet"
              detail="Try another city, category or budget to find your creative connection."
              action="Clear search & filters"
              onPress={store.resetSearch}
            />
          )
        }
      />
      <ChoiceModal
        visible={sortOpen}
        title="Sort talent"
        options={['Recommended', 'Price: low to high', 'Price: high to low', 'Name']}
        onSelect={(value) => setSort(value as SortOrder)}
        onClose={() => setSortOpen(false)}
      />
    </Screen>
  );
}
