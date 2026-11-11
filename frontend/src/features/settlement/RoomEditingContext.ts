import { createContext, useContext } from 'react';
export const RoomEditingContext = createContext<{readOnly: boolean; reportError: (error: unknown) => void}>({readOnly:false,reportError:()=>{}});
export const useRoomEditing = () => useContext(RoomEditingContext);
