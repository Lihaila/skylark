package core

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Store is the single source of truth, persisted as one JSON file.
type Store struct {
	mu    sync.Mutex
	file  string
	state AppState
}

func OpenStore(dir string) *Store {
	_ = os.MkdirAll(dir, 0o755)
	s := &Store{file: filepath.Join(dir, "state.json")}
	if data, err := os.ReadFile(s.file); err == nil && json.Unmarshal(data, &s.state) == nil {
		s.normalize()
		return s
	}
	// First run: China bypass and the Alibaba DNS only make sense for people in mainland China.
	s.state = DefaultState()
	if DetectCountry() == "CN" {
		s.state.Settings.BypassChina = true
		s.state.Settings.DirectDNS = "223.5.5.5"
	}
	s.save()
	return s
}

func (s *Store) normalize() {
	if s.state.Nodes == nil {
		s.state.Nodes = []Node{}
	}
	if s.state.Subs == nil {
		s.state.Subs = []Subscription{}
	}
	if s.state.Rules == nil {
		s.state.Rules = []Rule{}
	}
	if s.state.Mode == "" {
		s.state.Mode = ModeRule
	}
}

func (s *Store) State() AppState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.state
}

func (s *Store) Update(change func(*AppState)) AppState {
	s.mu.Lock()
	defer s.mu.Unlock()
	change(&s.state)
	s.normalize()
	s.save()
	return s.state
}

func (s *Store) save() {
	data, err := json.MarshalIndent(s.state, "", " ")
	if err != nil {
		return
	}
	tmp := s.file + ".tmp"
	if os.WriteFile(tmp, data, 0o600) == nil {
		_ = os.Rename(tmp, s.file)
	}
}

func (s *Store) AddNodes(nodes []Node) AppState {
	return s.Update(func(st *AppState) {
		st.Nodes = append(st.Nodes, nodes...)
		if st.SelectedID == "" && len(st.Nodes) > 0 {
			st.SelectedID = st.Nodes[0].ID
		}
	})
}

func (s *Store) DeleteNode(id string) AppState {
	return s.Update(func(st *AppState) {
		out := st.Nodes[:0]
		for _, n := range st.Nodes {
			if n.ID != id {
				out = append(out, n)
			}
		}
		st.Nodes = out
	})
}

// ReplaceSubNodes replaces all nodes of sub, keeping the selection if a node with the same name survives.
func (s *Store) ReplaceSubNodes(sub Subscription, nodes []Node) AppState {
	return s.Update(func(st *AppState) {
		selectedName := ""
		if sel := st.Selected(); sel != nil && sel.SubID == sub.ID {
			selectedName = sel.Name
		}
		now := time.Now()
		sub.UpdatedAt = &now
		kept := []Node{}
		for _, n := range st.Nodes {
			if n.SubID != sub.ID {
				kept = append(kept, n)
			}
		}
		for i := range nodes {
			nodes[i].SubID = sub.ID
		}
		st.Nodes = append(kept, nodes...)
		if selectedName != "" {
			for _, n := range nodes {
				if n.Name == selectedName {
					st.SelectedID = n.ID
					break
				}
			}
		}
		found := false
		for i := range st.Subs {
			if st.Subs[i].ID == sub.ID {
				st.Subs[i], found = sub, true
			}
		}
		if !found {
			st.Subs = append(st.Subs, sub)
		}
		if sel := st.Selected(); sel == nil || sel.ID != st.SelectedID {
			st.SelectedID = ""
			if len(st.Nodes) > 0 {
				st.SelectedID = st.Nodes[0].ID
			}
		}
	})
}

func (s *Store) DeleteSub(id string) AppState {
	return s.Update(func(st *AppState) {
		subs := []Subscription{}
		for _, x := range st.Subs {
			if x.ID != id {
				subs = append(subs, x)
			}
		}
		nodes := []Node{}
		for _, n := range st.Nodes {
			if n.SubID != id {
				nodes = append(nodes, n)
			}
		}
		st.Subs, st.Nodes = subs, nodes
	})
}
