import {
  Check,
  ChevronRight,
  CircleDollarSign,
  Copy,
  KeyRound,
  LogOut,
  Pencil,
  Plus,
  ReceiptText,
  RefreshCw,
  Share2,
  Trash2,
  Users,
  WalletCards,
  X
} from 'lucide-react';
import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import {
  addManualMember,
  createManualReceipt,
  createRoom,
  deleteItem,
  deleteMember,
  deleteReceipt,
  deleteRoom,
  getJoinOptions,
  getMembers,
  getReceipt,
  getReceipts,
  getSettlement,
  getSharedSettlement,
  issueInviteToken,
  issueShareToken,
  joinRoomWithOptions,
  login,
  replaceAssignees,
  signup,
  updateMemberName,
  updateReceipt,
  updateReceiptContents
} from './api';
import type {
  AuthSession,
  ReceiptDetail,
  ReceiptSummary,
  RecentRoom,
  RoomMember,
  Settlement,
  SharedSettlement
} from './types';

import {
  blankDraftItem,
  receiptItemsToDraftItems,
  normalizeReceiptDraft,
  calculateDraftTotal,
  getCreateAttempt,
  applyParticipants,
  setItemExcluded,
  type DraftItem
} from './receiptDraft';

const AUTH_KEY = 'itemsplit.auth';
const ROOMS_KEY = 'itemsplit.rooms';
const ACTIVE_ROOM_KEY = 'itemsplit.activeRoomId';

type MainTab = 'receipts' | 'settlement' | 'share';
type AuthMode = 'login' | 'signup' | 'shared';

type SettlementTransfer = {
  fromMemberId: number;
  fromNickname: string;
  toMemberId: number;
  toNickname: string;
  amount: number;
};

type Notice = {
  kind: 'success' | 'error' | 'info';
  text: string;
} | null;

const defaultDraftItems: DraftItem[] = [
  { name: '파스타', price: '15000', quantity: '1', memberIds: [], excludedFromSettlement: false },
  { name: '피자', price: '22000', quantity: '1', memberIds: [], excludedFromSettlement: false }
];
const DEFAULT_RECEIPT_NAME = '팀 회식';

function cloneDefaultDraftItems() {
  return defaultDraftItems.map((item) => ({ ...item }));
}

function readJson<T>(key: string, fallback: T): T {
  const raw = localStorage.getItem(key);
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function formatMoney(value: number | null | undefined) {
  return `${(value ?? 0).toLocaleString('ko-KR')}원`;
}

function formatDate(value: string | null | undefined) {
  if (!value) return '-';
  return value.slice(0, 10);
}

function numberOrNull(value: string) {
  const trimmed = value.trim();
  return trimmed === '' ? null : Number(trimmed);
}

function todayInputValue() {
  const now = new Date();
  const timezoneOffset = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - timezoneOffset).toISOString().slice(0, 10);
}

function calculateSettlementTransfers(members: Settlement['members']): SettlementTransfer[] {
  const debtors = members
    .filter((member) => member.net < 0)
    .map((member) => ({ member, amount: Math.abs(member.net) }))
    .sort((left, right) => right.amount - left.amount);
  const creditors = members
    .filter((member) => member.net > 0)
    .map((member) => ({ member, amount: member.net }))
    .sort((left, right) => right.amount - left.amount);
  const transfers: SettlementTransfer[] = [];

  let debtorIndex = 0;
  let creditorIndex = 0;
  while (debtorIndex < debtors.length && creditorIndex < creditors.length) {
    const debtor = debtors[debtorIndex];
    const creditor = creditors[creditorIndex];
    const amount = Math.min(debtor.amount, creditor.amount);

    if (amount > 0) {
      transfers.push({
        fromMemberId: debtor.member.memberId,
        fromNickname: debtor.member.nickname,
        toMemberId: creditor.member.memberId,
        toNickname: creditor.member.nickname,
        amount
      });
    }

    debtor.amount -= amount;
    creditor.amount -= amount;
    if (debtor.amount <= 0) debtorIndex += 1;
    if (creditor.amount <= 0) creditorIndex += 1;
  }

  return transfers;
}

function App() {
  const [auth, setAuth] = useState<AuthSession | null>(() => readJson<AuthSession | null>(AUTH_KEY, null));
  const [recentRooms, setRecentRooms] = useState<RecentRoom[]>(() => readJson<RecentRoom[]>(ROOMS_KEY, []));
  const [activeRoomId, setActiveRoomId] = useState<number | null>(() => {
    const value = localStorage.getItem(ACTIVE_ROOM_KEY);
    return value ? Number(value) : null;
  });
  const [activeTab, setActiveTab] = useState<MainTab>('receipts');
  const [notice, setNotice] = useState<Notice>(null);
  const [roomRefreshVersion, setRoomRefreshVersion] = useState(0);

  useEffect(() => {
    if (auth) {
      localStorage.setItem(AUTH_KEY, JSON.stringify(auth));
    } else {
      localStorage.removeItem(AUTH_KEY);
    }
  }, [auth]);

  useEffect(() => {
    localStorage.setItem(ROOMS_KEY, JSON.stringify(recentRooms));
  }, [recentRooms]);

  useEffect(() => {
    if (activeRoomId) {
      localStorage.setItem(ACTIVE_ROOM_KEY, String(activeRoomId));
    } else {
      localStorage.removeItem(ACTIVE_ROOM_KEY);
    }
  }, [activeRoomId]);

  const activeRoom = useMemo(
    () => recentRooms.find((room) => room.roomId === activeRoomId) ?? null,
    [activeRoomId, recentRooms]
  );

  function saveRoom(room: RecentRoom) {
    setRecentRooms((current) => {
      const next = [room, ...current.filter((item) => item.roomId !== room.roomId)];
      return next.slice(0, 8);
    });
    setActiveRoomId(room.roomId);
    setRoomRefreshVersion((version) => version + 1);
  }

  function removeRoom(roomId: number) {
    setRecentRooms((current) => {
      const next = current.filter((room) => room.roomId !== roomId);
      if (activeRoomId === roomId) {
        setActiveRoomId(next[0]?.roomId ?? null);
      }
      return next;
    });
  }

  function updateRoomOwnership(roomId: number, owner: boolean) {
    setRecentRooms((current) => current.map((room) => (room.roomId === roomId ? { ...room, owner } : room)));
  }

  function handleLogout() {
    setAuth(null);
    setActiveRoomId(null);
    setNotice({ kind: 'info', text: '로그아웃되었습니다.' });
  }

  if (!auth) {
    return <AuthScreen onAuthenticated={setAuth} notice={notice} setNotice={setNotice} />;
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand-lockup">
          <div>
            <strong>Item Split</strong>
            <span>- 모임 정산 서비스</span>
          </div>
        </div>
        <div className="topbar-actions">
          <span className="user-pill">{auth.nickname}</span>
          <button className="icon-button" type="button" title="로그아웃" onClick={handleLogout}>
            <LogOut size={18} />
          </button>
        </div>
      </header>

      <main className="workspace">
        <RoomRail
          token={auth.accessToken}
          rooms={recentRooms}
          activeRoomId={activeRoomId}
          saveRoom={saveRoom}
          removeRoom={removeRoom}
          setActiveRoomId={setActiveRoomId}
          setNotice={setNotice}
        />

        <section className="main-stage">
          {notice && <NoticeBar notice={notice} onClose={() => setNotice(null)} />}

          {activeRoom ? (
            <RoomWorkspace
              auth={auth}
              room={activeRoom}
              refreshVersion={roomRefreshVersion}
              activeTab={activeTab}
              setActiveTab={setActiveTab}
              removeRoom={removeRoom}
              updateRoomOwnership={updateRoomOwnership}
              setNotice={setNotice}
            />
          ) : (
            <EmptyRoomState />
          )}
        </section>
      </main>
    </div>
  );
}

function AuthScreen({
  onAuthenticated,
  notice,
  setNotice
}: {
  onAuthenticated: (auth: AuthSession) => void;
  notice: Notice;
  setNotice: (notice: Notice) => void;
}) {
  const [mode, setMode] = useState<AuthMode>('login');

  return (
    <div className="auth-screen">
      <section className="auth-panel">
        <div className="auth-copy">
          <div className="brand-lockup">
            <div>
              <strong>Item Split</strong>
              <span>- 모임 정산 서비스</span>
            </div>
          </div>
          <h1>더치페이 쉽고 간편하게</h1>
          <div className="hero-visual" aria-hidden="true">
            <div className="device-frame">
              <div className="receipt-sheet">
                <span />
                <span />
                <span />
                <strong>₩ 74,000</strong>
              </div>
              <div className="split-orbit">
                <b>A</b>
                <b>B</b>
                <b>C</b>
              </div>
            </div>
          </div>
        </div>

        <div className="auth-form-surface">
          <div className="pill-tabs">
            <button className={mode === 'login' ? 'active' : ''} type="button" onClick={() => setMode('login')}>
              로그인
            </button>
            <button className={mode === 'signup' ? 'active' : ''} type="button" onClick={() => setMode('signup')}>
              회원가입
            </button>
            <button className={mode === 'shared' ? 'active' : ''} type="button" onClick={() => setMode('shared')}>
              공유 정산
            </button>
          </div>

          {notice && <NoticeBar notice={notice} onClose={() => setNotice(null)} />}

          {mode === 'login' && <LoginForm onAuthenticated={onAuthenticated} setNotice={setNotice} />}
          {mode === 'signup' && <SignupForm onAuthenticated={onAuthenticated} setNotice={setNotice} />}
          {mode === 'shared' && <SharedLookup setNotice={setNotice} />}
        </div>
      </section>
    </div>
  );
}

function LoginForm({
  onAuthenticated,
  setNotice
}: {
  onAuthenticated: (auth: AuthSession) => void;
  setNotice: (notice: Notice) => void;
}) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setNotice(null);
    try {
      const session = await login({ email, password });
      onAuthenticated(session);
      setNotice({ kind: 'success', text: `${session.nickname} 계정으로 로그인했습니다.` });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '로그인에 실패했습니다.' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="stack-form" onSubmit={handleSubmit}>
      <Field label="이메일">
        <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" required />
      </Field>
      <Field label="비밀번호">
        <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" required />
      </Field>
      <button className="button primary" type="submit" disabled={submitting}>
        {submitting ? '확인 중' : '로그인'}
        <ChevronRight size={18} />
      </button>
    </form>
  );
}

function SignupForm({
  onAuthenticated,
  setNotice
}: {
  onAuthenticated: (auth: AuthSession) => void;
  setNotice: (notice: Notice) => void;
}) {
  const [email, setEmail] = useState('');
  const [nickname, setNickname] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setNotice(null);
    try {
      await signup({ email, nickname, password });
      const session = await login({ email, password });
      onAuthenticated(session);
      setNotice({ kind: 'success', text: `${session.nickname} 계정으로 가입하고 로그인했습니다.` });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '회원가입에 실패했습니다.' });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="stack-form" onSubmit={handleSubmit}>
      <Field label="이메일">
        <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" required />
      </Field>
      <Field label="닉네임">
        <input value={nickname} onChange={(event) => setNickname(event.target.value)} required maxLength={30} />
      </Field>
      <Field label="비밀번호">
        <input
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          type="password"
          minLength={8}
          required
        />
      </Field>
      <button className="button primary" type="submit" disabled={submitting}>
        {submitting ? '처리 중' : '계정 만들기'}
        <ChevronRight size={18} />
      </button>
    </form>
  );
}

function SharedLookup({ setNotice }: { setNotice: (notice: Notice) => void }) {
  const [shareToken, setShareToken] = useState('');
  const [settlement, setSettlement] = useState<SharedSettlement | null>(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setNotice(null);
    try {
      setSettlement(await getSharedSettlement(shareToken.trim()));
    } catch (error) {
      setSettlement(null);
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '공유 정산을 불러오지 못했습니다.' });
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="shared-lookup">
      <form className="stack-form" onSubmit={handleSubmit}>
        <Field label="공유 토큰">
          <input value={shareToken} onChange={(event) => setShareToken(event.target.value)} required />
        </Field>
        <button className="button blue" type="submit" disabled={loading}>
          {loading ? '조회 중' : '정산 보기'}
          <CircleDollarSign size={18} />
        </button>
      </form>
      {settlement && <SettlementBoard settlement={settlement} compact />}
    </div>
  );
}

function RoomRail({
  token,
  rooms,
  activeRoomId,
  saveRoom,
  removeRoom,
  setActiveRoomId,
  setNotice
}: {
  token: string;
  rooms: RecentRoom[];
  activeRoomId: number | null;
  saveRoom: (room: RecentRoom) => void;
  removeRoom: (roomId: number) => void;
  setActiveRoomId: (roomId: number) => void;
  setNotice: (notice: Notice) => void;
}) {
  const [roomName, setRoomName] = useState('');
  const [inviteToken, setInviteToken] = useState('');
  const [joinOptions, setJoinOptions] = useState<{ roomId: number; roomName: string; members: RoomMember[] } | null>(null);
  const [joinDialogOpen, setJoinDialogOpen] = useState(false);
  const [selectedJoinMemberId, setSelectedJoinMemberId] = useState('');
  const [joinNickname, setJoinNickname] = useState('');
  const [submitting, setSubmitting] = useState<'create' | 'join' | 'options' | null>(null);
  const [deletingRoomId, setDeletingRoomId] = useState<number | null>(null);

  async function handleCreate(event: FormEvent) {
    event.preventDefault();
    setSubmitting('create');
    setNotice(null);
    try {
      const room = await createRoom(token, roomName);
      saveRoom({ roomId: room.roomId, roomName: room.roomName, owner: true });
      setRoomName('');
      setNotice({ kind: 'success', text: `${room.roomName} 방을 만들었습니다.` });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '방 생성에 실패했습니다.' });
    } finally {
      setSubmitting(null);
    }
  }

  async function handleOpenJoinDialog(event: FormEvent) {
    event.preventDefault();
    setSubmitting('options');
    setNotice(null);
    try {
      const result = await getJoinOptions(token, inviteToken.trim());
      setJoinOptions(result);
      const firstUnlinked = result.members.find((member) => !member.linked);
      setSelectedJoinMemberId(firstUnlinked ? String(firstUnlinked.memberId) : '');
      setJoinNickname('');
      setJoinDialogOpen(true);
    } catch (error) {
      setJoinOptions(null);
      setJoinDialogOpen(false);
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '참가 옵션을 불러오지 못했습니다.' });
    } finally {
      setSubmitting(null);
    }
  }

  async function handleJoin() {
    if (!joinOptions) {
      return;
    }

    setSubmitting('join');
    setNotice(null);
    try {
      const room = await joinRoomWithOptions(
        token,
        inviteToken.trim(),
        selectedJoinMemberId ? Number(selectedJoinMemberId) : null,
        joinNickname || null
      );
      saveRoom({ roomId: room.roomId, roomName: room.roomName, owner: false });
      setInviteToken('');
      setJoinOptions(null);
      setJoinDialogOpen(false);
      setSelectedJoinMemberId('');
      setJoinNickname('');
      setNotice({ kind: 'success', text: `${room.roomName} 방에 연결되었습니다.` });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '방 참가에 실패했습니다.' });
    } finally {
      setSubmitting(null);
    }
  }

  async function handleDeleteRoom(room: RecentRoom) {
    if (!window.confirm(`${room.roomName} 방을 삭제할까요?\n영수증, 품목, 정산, 멤버 정보가 함께 삭제됩니다.`)) {
      return;
    }

    setDeletingRoomId(room.roomId);
    setNotice(null);
    try {
      await deleteRoom(token, room.roomId);
      removeRoom(room.roomId);
      setNotice({ kind: 'success', text: `${room.roomName} 방을 삭제했습니다.` });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '방 삭제에 실패했습니다.' });
    } finally {
      setDeletingRoomId(null);
    }
  }

  return (
    <aside className="room-rail">
      <div className="rail-heading">
        <span>Rooms</span>
        <strong>{rooms.length}</strong>
      </div>

      <form className="compact-form" onSubmit={handleCreate}>
        <Field label="새 방">
          <input
            value={roomName}
            onChange={(event) => setRoomName(event.target.value)}
            placeholder="팀 회식"
            maxLength={50}
            required
          />
        </Field>
        <button className="button primary full-width" type="submit" disabled={submitting === 'create'}>
          <Plus size={17} />
          만들기
        </button>
      </form>

      <form className="compact-form" onSubmit={handleOpenJoinDialog}>
        <Field label="초대 토큰">
          <input
            value={inviteToken}
            onChange={(event) => {
              setInviteToken(event.target.value);
              setJoinOptions(null);
              setJoinDialogOpen(false);
              setSelectedJoinMemberId('');
              setJoinNickname('');
            }}
            required
          />
        </Field>
        <button className="button secondary full-width" type="submit" disabled={!inviteToken || submitting === 'options'}>
          <KeyRound size={17} />
          {submitting === 'options' ? '확인 중' : '참가'}
        </button>
      </form>

      {joinDialogOpen && joinOptions && (
        <div className="modal-backdrop" role="presentation">
          <div className="join-dialog" role="dialog" aria-modal="true" aria-labelledby="join-dialog-title">
            <div className="panel-header">
              <div>
                <Users size={19} />
                <h3 id="join-dialog-title">{joinOptions.roomName}</h3>
              </div>
              <button
                className="icon-action"
                type="button"
                title="닫기"
                onClick={() => setJoinDialogOpen(false)}
                disabled={submitting === 'join'}
              >
                <X size={15} />
              </button>
            </div>

            <div className="join-choice-list">
              <label className={`join-choice ${selectedJoinMemberId === '' ? 'active' : ''}`}>
                <input
                  type="radio"
                  name="join-member"
                  value=""
                  checked={selectedJoinMemberId === ''}
                  onChange={() => setSelectedJoinMemberId('')}
                />
                <span>
                  <strong>새 멤버</strong>
                  <small>내 계정으로 새 참여자 추가</small>
                </span>
              </label>
              {joinOptions.members.map((member) => (
                <label
                  className={`join-choice ${selectedJoinMemberId === String(member.memberId) ? 'active' : ''}`}
                  key={member.memberId}
                >
                  <input
                    type="radio"
                    name="join-member"
                    value={member.memberId}
                    checked={selectedJoinMemberId === String(member.memberId)}
                    disabled={member.linked}
                    onChange={() => setSelectedJoinMemberId(String(member.memberId))}
                  />
                  <span>
                    <strong>{member.nickname}</strong>
                    <small>{member.linked ? '참여 완료' : '수동 멤버와 매칭'}</small>
                  </span>
                </label>
              ))}
            </div>

            <Field label="표시 이름">
              <input
                value={joinNickname}
                onChange={(event) => setJoinNickname(event.target.value)}
                placeholder={selectedJoinMemberId ? '기존 이름 유지' : '내 표시 이름'}
                maxLength={50}
              />
            </Field>

            <div className="dialog-actions">
              <button
                className="button ghost"
                type="button"
                onClick={() => setJoinDialogOpen(false)}
                disabled={submitting === 'join'}
              >
                취소
              </button>
              <button className="button primary" type="button" onClick={handleJoin} disabled={submitting === 'join'}>
                {submitting === 'join' ? '참가 중' : '참가 확정'}
                <Check size={17} />
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="room-list">
        {rooms.map((room) => (
          <div className="room-row" key={room.roomId}>
            <button
              className={`room-button ${activeRoomId === room.roomId ? 'active' : ''}`}
              type="button"
              onClick={() => setActiveRoomId(room.roomId)}
            >
              <span>{room.roomName}</span>
              <small>#{room.roomId}</small>
            </button>
            {room.owner && (
              <button
                className="icon-action danger room-delete-button"
                type="button"
                title="방 삭제"
                disabled={deletingRoomId !== null}
                onClick={() => handleDeleteRoom(room)}
              >
                <Trash2 size={15} />
              </button>
            )}
          </div>
        ))}
      </div>
    </aside>
  );
}

function RoomWorkspace({
  auth,
  room,
  refreshVersion,
  activeTab,
  setActiveTab,
  removeRoom,
  updateRoomOwnership,
  setNotice
}: {
  auth: AuthSession;
  room: RecentRoom;
  refreshVersion: number;
  activeTab: MainTab;
  setActiveTab: (tab: MainTab) => void;
  removeRoom: (roomId: number) => void;
  updateRoomOwnership: (roomId: number, owner: boolean) => void;
  setNotice: (notice: Notice) => void;
}) {
  const [members, setMembers] = useState<RoomMember[]>([]);
  const [receipts, setReceipts] = useState<ReceiptSummary[]>([]);
  const [selectedReceiptId, setSelectedReceiptId] = useState<number | null>(null);
  const [selectedReceipt, setSelectedReceipt] = useState<ReceiptDetail | null>(null);
  const [settlement, setSettlement] = useState<Settlement | null>(null);
  const [loading, setLoading] = useState(false);
  const [assignees, setAssignees] = useState<Record<number, number[]>>({});

  const token = auth.accessToken;

  async function refreshRoom() {
    setLoading(true);
    try {
      const [memberResult, receiptResult, settlementResult] = await Promise.all([
        getMembers(token, room.roomId),
        getReceipts(token, room.roomId),
        getSettlement(token, room.roomId)
      ]);
      setMembers(memberResult.members);
      setReceipts(receiptResult);
      setSettlement(settlementResult);
      updateRoomOwnership(
        room.roomId,
        memberResult.members.some((member) => member.owner && member.userId === auth.userId)
      );
      if (!selectedReceiptId && receiptResult[0]) {
        setSelectedReceiptId(receiptResult[0].receiptId);
      }
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '방 데이터를 불러오지 못했습니다.' });
    } finally {
      setLoading(false);
    }
  }

  async function refreshReceipt(receiptId = selectedReceiptId) {
    if (!receiptId) {
      setSelectedReceipt(null);
      return;
    }
    try {
      const detail = await getReceipt(token, room.roomId, receiptId);
      setSelectedReceipt(detail);
      setAssignees(Object.fromEntries(detail.items.map(item => [item.itemId, item.memberIds])));
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '영수증을 불러오지 못했습니다.' });
    }
  }

  useEffect(() => {
    setSelectedReceiptId(null);
    setSelectedReceipt(null);
    setAssignees({});
    void refreshRoom();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [room.roomId, refreshVersion]);

  useEffect(() => {
    void refreshReceipt();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedReceiptId]);

  const tabs: Array<{ id: MainTab; label: string; icon: typeof ReceiptText }> = [
    { id: 'receipts', label: '영수증', icon: ReceiptText },
    { id: 'settlement', label: '정산', icon: WalletCards },
    { id: 'share', label: '공유', icon: Share2 }
  ];

  return (
    <>
      <section className="room-hero">
        <div>
          <span className="eyebrow">#{room.roomId}</span>
          <h2>{room.roomName}</h2>
        </div>
        <div className="room-metrics">
          <Metric label="멤버" value={members.length} />
          <Metric label="영수증" value={receipts.length} />
          <Metric label="합계" value={settlement?.ready ? formatMoney(settlement.members.reduce((sum, member) => sum + member.burden, 0)) : '확인 필요'} />
        </div>
      </section>

      <MemberPanel
        token={token}
        roomId={room.roomId}
        currentUserId={auth.userId}
        members={members}
        refreshRoom={refreshRoom}
        onLeaveRoom={() => removeRoom(room.roomId)}
        setNotice={setNotice}
        className="room-member-panel"
      />

      <nav className="section-tabs" aria-label="방 메뉴">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          return (
            <button
              key={tab.id}
              type="button"
              className={activeTab === tab.id ? 'active' : ''}
              onClick={() => setActiveTab(tab.id)}
            >
              <Icon size={17} />
              {tab.label}
            </button>
          );
        })}
        <button className="ghost-refresh" type="button" onClick={refreshRoom} disabled={loading}>
          <RefreshCw size={17} />
          새로고침
        </button>
      </nav>

      {activeTab === 'receipts' && (
        <ReceiptsView
          token={token}
          roomId={room.roomId}
          members={members}
          receipts={receipts}
          selectedReceiptId={selectedReceiptId}
          setSelectedReceiptId={setSelectedReceiptId}
          selectedReceipt={selectedReceipt}
          assignees={assignees}
          setAssignees={setAssignees}
          refreshRoom={refreshRoom}
          refreshReceipt={refreshReceipt}
          setNotice={setNotice}
        />
      )}

      {activeTab === 'settlement' && <SettlementBoard settlement={settlement} />}

      {activeTab === 'share' && (
        <SharePanel
          token={token}
          room={room}
          setNotice={setNotice}
        />
      )}
    </>
  );
}

function ReceiptsView({
  token,
  roomId,
  members,
  receipts,
  selectedReceiptId,
  setSelectedReceiptId,
  selectedReceipt,
  assignees,
  setAssignees,
  refreshRoom,
  refreshReceipt,
  setNotice
}: {
  token: string;
  roomId: number;
  members: RoomMember[];
  receipts: ReceiptSummary[];
  selectedReceiptId: number | null;
  setSelectedReceiptId: (id: number | null) => void;
  selectedReceipt: ReceiptDetail | null;
  assignees: Record<number, number[]>;
  setAssignees: (value: Record<number, number[]> | ((current: Record<number, number[]>) => Record<number, number[]>)) => void;
  refreshRoom: () => Promise<void>;
  refreshReceipt: (receiptId?: number | null) => Promise<void>;
  setNotice: (notice: Notice) => void;
}) {
  const [receiptAction, setReceiptAction] = useState<number | null>(null);
  const [newDraftVersion, setNewDraftVersion] = useState(0);

  function handleNewReceiptDraft() {
    setSelectedReceiptId(null);
    void refreshReceipt(null);
    setNewDraftVersion((version) => version + 1);
  }

  async function handleDeleteReceipt(receipt: ReceiptSummary) {
    if (!window.confirm(`${receipt.name} 영수증을 삭제할까요?\n품목과 참여자 지정도 함께 삭제됩니다.`)) {
      return;
    }

    setReceiptAction(receipt.receiptId);
    setNotice(null);
    try {
      await deleteReceipt(token, roomId, receipt.receiptId);
      if (selectedReceiptId === receipt.receiptId) {
        setSelectedReceiptId(null);
        await refreshReceipt(null);
        setNewDraftVersion((version) => version + 1);
      }
      await refreshRoom();
      setNotice({ kind: 'success', text: '영수증을 삭제했습니다.' });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '영수증 삭제에 실패했습니다.' });
    } finally {
      setReceiptAction(null);
    }
  }

  return (
    <section className="receipt-grid">
      <ManualReceiptPanel
        token={token}
        roomId={roomId}
        members={members}
        editReceipt={selectedReceiptId === selectedReceipt?.receiptId ? selectedReceipt : null}
        newDraftVersion={newDraftVersion}
        setSelectedReceiptId={setSelectedReceiptId}
        refreshRoom={refreshRoom}
        refreshReceipt={refreshReceipt}
        setNotice={setNotice}
      />

      <div className="panel receipt-list-panel">
        <PanelHeader
          icon={<ReceiptText size={19} />}
          title="영수증"
          count={receipts.length}
          action={
            <button className="icon-action" type="button" title="새 영수증" onClick={handleNewReceiptDraft}>
              <Plus size={15} />
            </button>
          }
        />
        <div className="receipt-list">
          {receipts.map((receipt) => (
            <div
              key={receipt.receiptId}
              className={`receipt-row ${selectedReceiptId === receipt.receiptId ? 'active' : ''}`}
            >
              <button className="receipt-select" type="button" onClick={() => setSelectedReceiptId(receipt.receiptId)}>
                <span className="receipt-copy">
                  <strong>{receipt.name}</strong>
                  <small>{formatDate(receipt.purchasedAt ?? receipt.createdAt)}</small>
                </span>
                <b>{formatMoney(receipt.declaredTotal)}</b>
              </button>
              <button
                className="icon-action danger"
                type="button"
                title="영수증 삭제"
                disabled={receiptAction !== null}
                onClick={() => handleDeleteReceipt(receipt)}
              >
                <Trash2 size={15} />
              </button>
            </div>
          ))}
        </div>
      </div>

      <ReceiptDetailPanel
        token={token}
        roomId={roomId}
        members={members}
        receipt={selectedReceipt}
        assignees={assignees}
        setAssignees={setAssignees}
        refreshRoom={refreshRoom}
        refreshReceipt={refreshReceipt}
        setNotice={setNotice}
      />
    </section>
  );
}

export function ManualReceiptPanel({
  token,
  roomId,
  members,
  editReceipt,
  newDraftVersion,
  setSelectedReceiptId,
  refreshRoom,
  refreshReceipt,
  setNotice
}: {
  token: string;
  roomId: number;
  members: RoomMember[];
  editReceipt: ReceiptDetail | null;
  newDraftVersion: number;
  setSelectedReceiptId: (id: number | null) => void;
  refreshRoom: () => Promise<void>;
  refreshReceipt: (receiptId?: number | null) => Promise<void>;
  setNotice: (notice: Notice) => void;
}) {
  const [name, setName] = useState(DEFAULT_RECEIPT_NAME);
  const [payerMemberId, setPayerMemberId] = useState('');
  const [participantIds, setParticipantIds] = useState<number[]>([]);
  const [purchasedAt, setPurchasedAt] = useState(todayInputValue);
  const [items, setItems] = useState<DraftItem[]>(() => cloneDefaultDraftItems());
  const [declaredTotal, setDeclaredTotal] = useState('');
  const createAttempt = useRef<ReturnType<typeof getCreateAttempt> | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const ownerPayerId = useMemo(() => members.find((member) => member.owner)?.memberId ?? null, [members]);
  const isEditing = editReceipt !== null;
  const itemTotal = useMemo(() => calculateDraftTotal(items), [items]);
  useEffect(() => {
    if (editReceipt) {
      setName(editReceipt.name);
      setDeclaredTotal(editReceipt.declaredTotal === null ? '' : String(editReceipt.declaredTotal));
      setParticipantIds([]);
      setPayerMemberId(editReceipt.payerMemberId !== null ? String(editReceipt.payerMemberId) : '');
      setPurchasedAt(editReceipt.purchasedAt?.slice(0, 10) ?? '');
      setItems(receiptItemsToDraftItems(editReceipt));
      return;
    }

    createAttempt.current = null;
    setDeclaredTotal('');
    setName(DEFAULT_RECEIPT_NAME);
    setPayerMemberId(ownerPayerId !== null ? String(ownerPayerId) : '');
    setParticipantIds([]);
    setPurchasedAt(todayInputValue());
    setItems(cloneDefaultDraftItems());
  }, [roomId, editReceipt, newDraftVersion]);

  useEffect(() => {
    setParticipantIds((current) => current.filter((memberId) => members.some((member) => member.memberId === memberId)));
  }, [members]);

  function updateDraft(index: number, patch: Partial<DraftItem>) {
    setItems((current) => current.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item)));
  }

  function removeDraft(index: number) {
    setItems((current) => current.filter((_, itemIndex) => itemIndex !== index));
  }

  function toggleParticipant(memberId: number) {
    setParticipantIds((current) =>
      current.includes(memberId) ? current.filter((id) => id !== memberId) : [...current, memberId]
    );
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setNotice(null);
    try {
      const payload = normalizeReceiptDraft({ name, payerMemberId, declaredTotal, purchasedAt, items });
      if (editReceipt) {
        await updateReceiptContents(token, roomId, editReceipt.receiptId, payload);
        await refreshRoom();
        await refreshReceipt(editReceipt.receiptId);
        setNotice({ kind: 'success', text: '영수증을 수정했습니다.' });
        return;
      }

      createAttempt.current = getCreateAttempt(createAttempt.current, payload);
      const created = await createManualReceipt(token, roomId, {
        ...payload,
        requestId: createAttempt.current.requestId,
        items: payload.items.map(({ itemId: _itemId, ...item }) => item)
      });
      await refreshRoom();
      setSelectedReceiptId(created.receiptId);
      await refreshReceipt(created.receiptId);
      setNotice({ kind: 'success', text: '영수증을 추가했습니다.' });
    } catch (error) {
      setNotice({
        kind: 'error',
        text: error instanceof Error ? error.message : isEditing ? '영수증 수정에 실패했습니다.' : '영수증 추가에 실패했습니다.'
      });
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="panel manual-panel" onSubmit={handleSubmit}>
      <fieldset className="receipt-fieldset" disabled={submitting}>
        <PanelHeader icon={<Plus size={19} />} title={isEditing ? "영수증 수정" : "수동 입력"} />
        <Field label="상호명">
          <input
            value={name}
            onFocus={() => {
              if (name === DEFAULT_RECEIPT_NAME) {
                setName('');
              }
            }}
            onBlur={(event) => {
              if (!event.currentTarget.value.trim()) {
                setName(DEFAULT_RECEIPT_NAME);
              }
            }}
            onChange={(event) => setName(event.target.value)}
            maxLength={100}
            required
          />
        </Field>
        <div className="field-row">
          <Field label="결제자">
            <select value={payerMemberId} onChange={(event) => setPayerMemberId(event.target.value)}>
              <option value="">미지정</option>
              {members.map((member) => (
                <option value={member.memberId} key={member.memberId}>
                  {member.nickname}
                </option>
              ))}
            </select>
          </Field>
          <Field label="원본 총액 (미입력 가능)">
            <input value={declaredTotal} onChange={event => setDeclaredTotal(event.target.value)} inputMode="numeric" placeholder="미확정" />
          </Field>
        </div>
        <div className="total-confirmation">
          <span>전체 품목 합계: {itemTotal === null ? '입력 확인 필요' : formatMoney(itemTotal)}</span>
          {(!editReceipt || editReceipt.sourceType === 'MANUAL') && (
            <button className="button ghost dense" type="button" disabled={itemTotal === null || itemTotal <= 0}
              onClick={() => { if (itemTotal !== null) setDeclaredTotal(String(itemTotal)); }}>품목 합계로 총액 확인</button>
          )}
          {editReceipt?.sourceType === 'IMAGE_UPLOAD' && <small>이미지에 적힌 원본 총액을 직접 입력해주세요.</small>}
          <small>제외 품목도 합계에 포함됩니다. 원본 총액은 품목을 수정해도 바뀌지 않습니다.</small>
          {declaredTotal && itemTotal !== null && Number(declaredTotal) !== itemTotal && <span className="warning-badge">원본 총액과 품목 합계가 다릅니다. 초안 저장 후 수정할 수 있습니다.</span>}
        </div>
        <div className="field manual-assignee-field">
          <span>참여자 일괄 지정</span>
          <div className="member-chip-row manual-assignee-row">
            {members.map((member) => {
              const checked = participantIds.includes(member.memberId);
              return (
                <button
                  className={`member-chip ${checked ? 'active' : ''}`}
                  key={member.memberId}
                  type="button"
                  aria-pressed={checked}
                  onClick={() => toggleParticipant(member.memberId)}
                >
                  {member.nickname}
                </button>
              );
            })}
          </div>
          <button className="button ghost dense" type="button"
            onClick={() => setItems(current => applyParticipants(current, participantIds))}>
            선택한 참여자를 모든 정산 품목에 적용
          </button>
          <small>위 버튼을 누를 때만 기존 품목별 참여자가 바뀝니다.</small>
        </div>
        <Field label="구매일">
          <input value={purchasedAt} onChange={(event) => setPurchasedAt(event.target.value)} type="date" />
        </Field>
        <div className="draft-items">
          {items.map((item, index) => (
            <div className="draft-item" key={item.itemId ?? `new-${index}`}>
              <input
                value={item.name}
                onChange={(event) => updateDraft(index, { name: event.target.value })}
                placeholder="품목명"
                required
              />
              <input
                className="price-input"
                value={item.price}
                onChange={(event) => updateDraft(index, { price: event.target.value })}
                type="text"
                inputMode="numeric"
                pattern="[0-9]*"
                placeholder="가격"
                required
              />
              <input
                value={item.quantity}
                onChange={(event) => updateDraft(index, { quantity: event.target.value })}
                type="text"
                inputMode="numeric"
                pattern="[0-9]*"
                placeholder="수량"
                required
              />
              <button
                className="icon-action danger draft-remove-button"
                type="button"
                title="품목 삭제"
                onClick={() => removeDraft(index)}
              >
                <Trash2 size={15} />
              </button>
              <div className="draft-item-assignment">
                <label className="exclusion-toggle">
                  <input type="checkbox" checked={item.excludedFromSettlement}
                    onChange={event => updateDraft(index, setItemExcluded(item, event.target.checked))} />
                  정산에서 제외
                </label>
                {item.excludedFromSettlement ? (
                  <small>참여자 지정을 해제하고 부담·결제 금액에서 제외합니다. 다시 포함하면 참여자를 선택해주세요.</small>
                ) : (
                  <>
                    <div className="member-chip-row">
                      {members.map(member => {
                        const selected = item.memberIds.includes(member.memberId);
                        return <button key={member.memberId} type="button" aria-pressed={selected}
                          className={`member-chip ${selected ? 'active' : ''}`}
                          onClick={() => updateDraft(index, { memberIds: selected ? item.memberIds.filter(id => id !== member.memberId) : [...item.memberIds, member.memberId] })}>
                          {member.nickname}
                        </button>;
                      })}
                    </div>
                    {item.memberIds.length === 0 && <small>참여자 미지정: 저장할 수 있지만 송금 정산 전 지정이 필요합니다.</small>}
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
        <button
          className="button ghost full-width"
          type="button"
          onClick={() => setItems((current) => [...current, blankDraftItem()])}
        >
          <Plus size={17} />
          품목 추가
        </button>
        <button className="button blue full-width" type="submit" disabled={submitting}>
          {submitting ? '저장 중' : isEditing ? '영수증 수정' : '영수증 저장'}
          <Check size={17} />
        </button>
      </fieldset>
    </form>
  );
}

function ReceiptDetailPanel({
  token,
  roomId,
  members,
  receipt,
  assignees,
  setAssignees,
  refreshRoom,
  refreshReceipt,
  setNotice
}: {
  token: string;
  roomId: number;
  members: RoomMember[];
  receipt: ReceiptDetail | null;
  assignees: Record<number, number[]>;
  setAssignees: (value: Record<number, number[]> | ((current: Record<number, number[]>) => Record<number, number[]>)) => void;
  refreshRoom: () => Promise<void>;
  refreshReceipt: (receiptId?: number | null) => Promise<void>;
  setNotice: (notice: Notice) => void;
}) {
  const [editPayerMemberId, setEditPayerMemberId] = useState('');
  const [savingReceiptMeta, setSavingReceiptMeta] = useState(false);
  const [savingAssigneeItemId, setSavingAssigneeItemId] = useState<number | null>(null);

  useEffect(() => {
    setEditPayerMemberId(receipt?.payerMemberId !== null && receipt?.payerMemberId !== undefined ? String(receipt.payerMemberId) : '');
  }, [receipt?.receiptId, receipt?.payerMemberId]);

  if (!receipt) {
    return (
      <div className="panel detail-panel">
        <PanelHeader icon={<ReceiptText size={19} />} title="상세" />
        <div className="empty-box">영수증을 선택하세요.</div>
      </div>
    );
  }

  const currentReceipt = receipt;
  const itemTotal = currentReceipt.items.reduce((sum, item) => sum + item.price * item.quantity, 0);
  const payerMembers = members;
  const currentPayerInMembers =
    currentReceipt.payerMemberId === null || payerMembers.some((member) => member.memberId === currentReceipt.payerMemberId);

  async function handleUpdateReceiptPayer(nextPayerMemberId: string) {
    setEditPayerMemberId(nextPayerMemberId);
    setSavingReceiptMeta(true);
    setNotice(null);
    try {
      await updateReceipt(token, roomId, currentReceipt.receiptId, {
        name: currentReceipt.name,
        payerMemberId: numberOrNull(nextPayerMemberId),
        declaredTotal: currentReceipt.declaredTotal,
        purchasedAt: currentReceipt.purchasedAt
      });
      await refreshReceipt(currentReceipt.receiptId);
      await refreshRoom();
      setNotice({ kind: 'success', text: '결제자를 수정했습니다.' });
    } catch (error) {
      setEditPayerMemberId(currentReceipt.payerMemberId !== null ? String(currentReceipt.payerMemberId) : '');
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '결제자 수정에 실패했습니다.' });
    } finally {
      setSavingReceiptMeta(false);
    }
  }

  async function toggleAssignee(itemId: number, memberId: number, selectedIds: number[]) {
    const nextIds = selectedIds.includes(memberId)
      ? selectedIds.filter((id) => id !== memberId)
      : [...selectedIds, memberId];

    setAssignees((current) => ({ ...current, [itemId]: nextIds }));
    setSavingAssigneeItemId(itemId);
    setNotice(null);
    try {
      await replaceAssignees(token, roomId, currentReceipt.receiptId, itemId, nextIds);
      await refreshReceipt(currentReceipt.receiptId);
      await refreshRoom();
      setNotice({ kind: 'success', text: '참여자를 저장했습니다.' });
    } catch (error) {
      await refreshReceipt(currentReceipt.receiptId);
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '참여자 저장에 실패했습니다.' });
    } finally {
      setSavingAssigneeItemId(null);
    }
  }

  async function removeItem(itemId: number) {
    try {
      await deleteItem(token, roomId, currentReceipt.receiptId, itemId);
      await refreshReceipt(currentReceipt.receiptId);
      await refreshRoom();
      setNotice({ kind: 'success', text: '품목을 삭제했습니다.' });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '품목 삭제에 실패했습니다.' });
    }
  }

  return (
    <div className="panel detail-panel">
      <PanelHeader icon={<ReceiptText size={19} />} title={currentReceipt.name} count={currentReceipt.items.length} />
      <div className="receipt-total-strip">
        <span>품목 합계</span>
        <strong>{formatMoney(itemTotal)}</strong>
      </div>
      <div className="receipt-meta-form">
        <Field label="결제자">
          <select
            value={editPayerMemberId}
            onChange={(event) => handleUpdateReceiptPayer(event.target.value)}
            disabled={savingReceiptMeta}
          >
            <option value="">미지정</option>
            {!currentPayerInMembers && currentReceipt.payerMemberId !== null && (
              <option value={currentReceipt.payerMemberId}>{currentReceipt.payerNickname ?? '이전 결제자'}</option>
            )}
            {payerMembers.map((member) => (
              <option value={member.memberId} key={member.memberId}>
                {member.nickname}
              </option>
            ))}
          </select>
        </Field>
        {savingReceiptMeta && <span className="autosave-status">저장 중</span>}
      </div>
      {currentReceipt.warning && <div className="warning-badge">{currentReceipt.warning}</div>}

      <div className="item-stack">
        {currentReceipt.items.map((item) => {
          const selected = assignees[item.itemId] ?? [];
          return (
            <div className="item-row" key={item.itemId}>
              <div className="item-main">
                <strong>{item.name}</strong>
                <span>
                  {formatMoney(item.price)} × {item.quantity}
                </span>
              </div>
              {item.excludedFromSettlement && <small className="warning-badge">정산 제외 · 수정 폼에서 다시 포함할 수 있습니다.</small>}
              <div className="member-chip-row">
                {members.map((member) => {
                  const checked = selected.includes(member.memberId);
                  return (
                    <button
                      type="button"
                      className={`member-chip ${checked ? 'active' : ''}`}
                      key={member.memberId}
                      disabled={savingAssigneeItemId !== null || item.excludedFromSettlement}
                      onClick={() => toggleAssignee(item.itemId, member.memberId, selected)}
                    >
                      {member.nickname}
                    </button>
                  );
                })}
              </div>
              <div className="row-actions">
                <button className="button text-danger dense" type="button" onClick={() => removeItem(item.itemId)}>
                  삭제
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export function SettlementBoard({ settlement, compact = false }: { settlement: Settlement | SharedSettlement | null; compact?: boolean }) {
  if (!settlement) {
    return (
      <section className="panel settlement-panel">
        <PanelHeader icon={<WalletCards size={19} />} title="정산" />
        <div className="empty-box">정산 결과가 없습니다.</div>
      </section>
    );
  }

  if ('ready' in settlement && !settlement.ready) {
    return (
      <section className="panel settlement-panel">
        <PanelHeader icon={<WalletCards size={19} />} title="정산 준비가 필요합니다" />
        <p className="draft-help">아래 내용을 수정하면 송금 정산을 확인할 수 있습니다.</p>
        <ul className="settlement-issues">
          {settlement.issues.map((issue, index) => (
            <li key={`${issue.code}-${issue.receiptId}-${issue.itemId}-${index}`}>
              <strong>영수증 #{issue.receiptId}{issue.itemId !== null ? ` · 품목 #${issue.itemId}` : ''}</strong>
              <span>{issue.message}</span>
            </li>
          ))}
        </ul>
      </section>
    );
  }

  const transfers = calculateSettlementTransfers(settlement.members);

  return (
    <section className={`panel settlement-panel ${compact ? 'compact' : ''}`}>
      <PanelHeader icon={<WalletCards size={19} />} title={settlement.roomName} count={settlement.members.length} />
      <div className="settlement-list">
        {settlement.members.map((member) => (
          <div className="settlement-row" key={member.memberId}>
            <div>
              <strong>{member.nickname}</strong>
              <span>부담 {formatMoney(member.burden)} · 결제 {formatMoney(member.paid)}</span>
            </div>
            <b className={member.net >= 0 ? 'positive' : 'negative'}>{formatMoney(member.net)}</b>
          </div>
        ))}
      </div>
      <div className="transfer-section">
        <div className="section-mini-heading">
          <span>송금</span>
          <strong>{transfers.length}</strong>
        </div>
        {transfers.length > 0 ? (
          <div className="transfer-list">
            {transfers.map((transfer) => (
              <div
                className="transfer-row"
                key={`${transfer.fromMemberId}-${transfer.toMemberId}-${transfer.amount}`}
              >
                <div className="transfer-route">
                  <strong>{transfer.fromNickname}</strong>
                  <ChevronRight size={16} />
                  <strong>{transfer.toNickname}</strong>
                </div>
                <b>{formatMoney(transfer.amount)}</b>
              </div>
            ))}
          </div>
        ) : (
          <div className="transfer-empty">송금할 내역이 없습니다.</div>
        )}
      </div>
    </section>
  );
}

function SharePanel({
  token,
  room,
  setNotice
}: {
  token: string;
  room: RecentRoom;
  setNotice: (notice: Notice) => void;
}) {
  const [invite, setInvite] = useState<{ token: string; expiresAt: string } | null>(null);
  const [share, setShare] = useState<{ token: string; expiresAt: string } | null>(null);
  const [loading, setLoading] = useState<'invite' | 'share' | null>(null);

  async function copyTokenSilently(value: string) {
    try {
      await navigator.clipboard?.writeText(value);
      return true;
    } catch {
      return false;
    }
  }

  async function handleInvite() {
    setLoading('invite');
    try {
      const result = await issueInviteToken(token, room.roomId);
      setInvite({ token: result.token, expiresAt: result.expiresAt });
      const copied = await copyTokenSilently(result.token);
      setNotice({
        kind: 'success',
        text: copied ? '초대 토큰을 발급하고 클립보드에 복사했습니다.' : '초대 토큰을 발급했습니다.'
      });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '초대 토큰 발급에 실패했습니다.' });
    } finally {
      setLoading(null);
    }
  }

  async function handleShare() {
    setLoading('share');
    try {
      const result = await issueShareToken(token, room.roomId);
      setShare({ token: result.token, expiresAt: result.expiresAt });
      const copied = await copyTokenSilently(result.token);
      setNotice({
        kind: 'success',
        text: copied ? '공유 토큰을 발급하고 클립보드에 복사했습니다.' : '공유 토큰을 발급했습니다.'
      });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '공유 토큰 발급에 실패했습니다.' });
    } finally {
      setLoading(null);
    }
  }

  async function copyText(value: string) {
    await navigator.clipboard?.writeText(value);
    setNotice({ kind: 'success', text: '클립보드에 복사했습니다.' });
  }

  return (
    <section className="share-grid">
      <div className="panel token-panel">
        <PanelHeader icon={<KeyRound size={19} />} title="초대" />
        <button className="button primary full-width" type="button" onClick={handleInvite} disabled={loading === 'invite'}>
          <KeyRound size={17} />
          토큰 발급
        </button>
        {invite && <TokenBox token={invite.token} expiresAt={invite.expiresAt} onCopy={copyText} />}
      </div>

      <div className="panel token-panel">
        <PanelHeader icon={<Share2 size={19} />} title="읽기 전용 공유" />
        <button className="button blue full-width" type="button" onClick={handleShare} disabled={loading === 'share'}>
          <Share2 size={17} />
          공유 링크 발급
        </button>
        {share && <TokenBox token={share.token} expiresAt={share.expiresAt} onCopy={copyText} />}
      </div>
    </section>
  );
}

function MemberPanel({
  token,
  roomId,
  currentUserId,
  members,
  refreshRoom,
  onLeaveRoom,
  setNotice,
  className = ''
}: {
  token: string;
  roomId: number;
  currentUserId: number;
  members: RoomMember[];
  refreshRoom: () => Promise<void>;
  onLeaveRoom: () => void;
  setNotice: (notice: Notice) => void;
  className?: string;
}) {
  const [manualName, setManualName] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [editingMemberId, setEditingMemberId] = useState<number | null>(null);
  const [editingName, setEditingName] = useState('');
  const [memberAction, setMemberAction] = useState<string | null>(null);

  async function handleAddManualMember(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setNotice(null);
    try {
      await addManualMember(token, roomId, manualName.trim());
      setManualName('');
      await refreshRoom();
      setNotice({ kind: 'success', text: '수동 멤버를 추가했습니다.' });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '수동 멤버 추가에 실패했습니다.' });
    } finally {
      setSubmitting(false);
    }
  }

  function startEditing(member: RoomMember) {
    setEditingMemberId(member.memberId);
    setEditingName(member.nickname);
  }

  async function handleUpdateMemberName(event: FormEvent) {
    event.preventDefault();
    if (!editingMemberId) {
      return;
    }

    setMemberAction(`edit-${editingMemberId}`);
    setNotice(null);
    try {
      await updateMemberName(token, roomId, editingMemberId, editingName.trim());
      setEditingMemberId(null);
      setEditingName('');
      await refreshRoom();
      setNotice({ kind: 'success', text: '멤버 이름을 수정했습니다.' });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '멤버 이름 수정에 실패했습니다.' });
    } finally {
      setMemberAction(null);
    }
  }

  async function handleDeleteMember(member: RoomMember) {
    const deletingSelf = member.userId === currentUserId;
    const confirmMessage = deletingSelf
      ? `${member.nickname} 계정으로 이 방에서 나갈까요?\n초대 토큰으로 다시 참여할 수 있습니다.`
      : `${member.nickname} 멤버를 방에서 제외할까요?\n초대 토큰으로 다시 참여할 수 있습니다.`;

    if (!window.confirm(confirmMessage)) {
      return;
    }

    setMemberAction(`delete-${member.memberId}`);
    setNotice(null);
    try {
      await deleteMember(token, roomId, member.memberId);
      if (editingMemberId === member.memberId) {
        setEditingMemberId(null);
        setEditingName('');
      }
      if (deletingSelf) {
        onLeaveRoom();
        setNotice({ kind: 'success', text: '방에서 나갔습니다.' });
        return;
      }
      await refreshRoom();
      setNotice({ kind: 'success', text: '멤버를 방에서 제외했습니다.' });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '멤버 제외에 실패했습니다.' });
    } finally {
      setMemberAction(null);
    }
  }

  return (
    <section className={`panel member-panel ${className}`}>
      <PanelHeader icon={<Users size={19} />} title="멤버" count={members.length} />
      <form className="manual-member-form" onSubmit={handleAddManualMember}>
        <input
          value={manualName}
          onChange={(event) => setManualName(event.target.value)}
          placeholder="멤버 이름 수동 입력"
          maxLength={50}
          required
        />
        <button className="button primary dense" type="submit" disabled={submitting}>
          {submitting ? '추가 중' : '추가'}
        </button>
      </form>
      <div className="member-list">
        {members.map((member) => {
          const editing = editingMemberId === member.memberId;
          const deletingSelf = member.userId === currentUserId;
          const canDeleteMember = !member.owner;
          return (
            <div className={`member-row ${editing ? 'editing' : ''}`} key={member.memberId}>
              {editing ? (
                <form className="member-edit-form" onSubmit={handleUpdateMemberName}>
                  <input
                    value={editingName}
                    onChange={(event) => setEditingName(event.target.value)}
                    maxLength={50}
                    required
                    autoFocus
                  />
                  <button className="button blue dense" type="submit" disabled={memberAction !== null}>
                    <Check size={15} />
                    저장
                  </button>
                  <button
                    className="button ghost dense"
                    type="button"
                    disabled={memberAction !== null}
                    onClick={() => {
                      setEditingMemberId(null);
                      setEditingName('');
                    }}
                  >
                    <X size={15} />
                    취소
                  </button>
                </form>
              ) : (
                <>
                  <div className="member-copy">
                    <span>{member.nickname}</span>
                    <small>{member.owner ? 'owner' : member.linked ? member.nickname : '수동 멤버'}</small>
                  </div>
                  <div className="member-actions">
                    <button
                      className="icon-action"
                      type="button"
                      title="이름 수정"
                      disabled={memberAction !== null}
                      onClick={() => startEditing(member)}
                    >
                      <Pencil size={15} />
                    </button>
                    {canDeleteMember && (
                      <button
                        className="icon-action danger"
                        type="button"
                        title={deletingSelf ? '방 나가기' : '멤버 제외'}
                        disabled={memberAction !== null}
                        onClick={() => handleDeleteMember(member)}
                      >
                        <Trash2 size={15} />
                      </button>
                    )}
                  </div>
                </>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}

function TokenBox({
  token,
  expiresAt,
  onCopy
}: {
  token: string;
  expiresAt: string;
  onCopy: (value: string) => void;
}) {
  return (
    <div className="token-box">
      <code>{token}</code>
      <span>{formatDate(expiresAt)} 만료</span>
      <button className="icon-button" type="button" title="복사" onClick={() => onCopy(token)}>
        <Copy size={16} />
      </button>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
    </label>
  );
}

function PanelHeader({
  icon,
  title,
  count,
  action
}: {
  icon: React.ReactNode;
  title: string;
  count?: number;
  action?: React.ReactNode;
}) {
  return (
    <div className="panel-header">
      <div>
        {icon}
        <h3>{title}</h3>
      </div>
      <div className="panel-header-actions">
        {typeof count === 'number' && <span className="count-pill">{count}</span>}
        {action}
      </div>
    </div>
  );
}

function NoticeBar({ notice, onClose }: { notice: NonNullable<Notice>; onClose: () => void }) {
  return (
    <div className={`notice ${notice.kind}`}>
      <span>{notice.text}</span>
      <button type="button" onClick={onClose}>
        닫기
      </button>
    </div>
  );
}

function EmptyRoomState() {
  return (
    <section className="empty-state">
      <div className="empty-visual" aria-hidden="true">
        <ReceiptText size={48} />
      </div>
      <h2>방을 선택하세요.</h2>
    </section>
  );
}

export default App;
