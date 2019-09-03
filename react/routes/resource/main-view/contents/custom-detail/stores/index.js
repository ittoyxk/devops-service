import React, { createContext, useContext, useEffect, useMemo } from 'react';
import { DataSet } from 'choerodon-ui/pro';
import { inject } from 'mobx-react';
import { injectIntl } from 'react-intl';
import { observer } from 'mobx-react-lite';
import { useResourceStore } from '../../../../stores';
import DetailDataSet from './DetailDataSet';

const Store = createContext();

export function useCustomDetailStore() {
  return useContext(Store);
}

export const StoreProvider = injectIntl(inject('AppState')(
  observer((props) => {
    const { AppState: { currentMenuType: { id } }, children } = props;
    const { resourceStore: { getSelectedMenu: { menuId } } } = useResourceStore();
    const detailDs = useMemo(() => new DataSet(DetailDataSet()), []);

    useEffect(() => {
      detailDs.transport.read.url = `/devops/v1/projects/${id}/customize_resource/${menuId}`;
      detailDs.query();
    }, [id, menuId]);

    const value = {
      ...props,
      detailDs,
    };

    return (
      <Store.Provider value={value}>
        {children}
      </Store.Provider>
    );
  })
));
