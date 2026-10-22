import React from 'react';
import { AuthGuard } from '../src/auth/Guard';
import TalentEditor from '../src/screens/TalentEditor';
export default function EditTalent() {
  return (
    <AuthGuard role="talent">
      <TalentEditor />
    </AuthGuard>
  );
}
