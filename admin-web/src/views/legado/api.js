import { ajaxGet, ajaxPost, ajaxPut, ajaxDelete, uploadFile } from '@/api/request.js';

const api = {};

// 备份
api.backupList = params => ajaxGet({ url: '/api/legado/backups/', params });
api.backupDelete = params => ajaxDelete({ url: '/api/legado/backups/', params });
api.backupItems = id => ajaxGet({ url: `/api/legado/backups/${id}/items/`, params: {} });
api.backupRestore = id => ajaxPost({ url: `/api/legado/backups/${id}/restore/`, params: {} });

// 用户
api.userList = params => ajaxGet({ url: '/api/legado/users/', params });
api.userEdit = params => ajaxPut({ url: '/api/legado/users/', params });
api.userDelete = params => ajaxDelete({ url: '/api/legado/users/', params });

// 书架
api.bookList = params => ajaxGet({ url: '/api/legado/books/', params });
api.bookDelete = params => ajaxDelete({ url: '/api/legado/books/', params });
api.bookBookmarks = id => ajaxGet({ url: `/api/legado/books/${id}/bookmarks/`, params: {} });
api.bookHighlights = id => ajaxGet({ url: `/api/legado/books/${id}/highlights/`, params: {} });

// 用户配置
api.userConfigList = params => ajaxGet({ url: '/api/legado/user-configs/', params });
api.userConfigEdit = params => ajaxPut({ url: '/api/legado/user-configs/', params });
api.userConfigDelete = params => ajaxDelete({ url: '/api/legado/user-configs/', params });

// 书源
api.bookSourceList = params => ajaxGet({ url: '/api/legado/book-sources/', params });
api.bookSourceAdd = params => ajaxPost({ url: '/api/legado/book-sources/', params });
api.bookSourceEdit = params => ajaxPut({ url: '/api/legado/book-sources/', params });
api.bookSourceDelete = params => ajaxDelete({ url: '/api/legado/book-sources/', params });

// 用户自定义书源
api.userBookSourceList = params => ajaxGet({ url: '/api/legado/user-book-sources/', params });
api.userBookSourceDelete = params => ajaxDelete({ url: '/api/legado/user-book-sources/', params });

// TTS 引擎
api.ttsList = params => ajaxGet({ url: '/api/legado/tts-engines/', params });
api.ttsAdd = params => ajaxPost({ url: '/api/legado/tts-engines/', params });
api.ttsEdit = params => ajaxPut({ url: '/api/legado/tts-engines/', params });
api.ttsDelete = params => ajaxDelete({ url: '/api/legado/tts-engines/', params });
api.ttsUpload = (id, formData, onProgress) =>
  uploadFile({ url: `/api/legado/tts-engines/${id}/upload_package/`, formData }, onProgress);

// 字体市场
api.fontList = params => ajaxGet({ url: '/api/legado/font-packs/', params });
api.fontAdd = params => ajaxPost({ url: '/api/legado/font-packs/', params });
api.fontEdit = params => ajaxPut({ url: '/api/legado/font-packs/', params });
api.fontDelete = params => ajaxDelete({ url: '/api/legado/font-packs/', params });
api.fontUpload = (id, formData, onProgress) =>
  uploadFile({ url: `/api/legado/font-packs/${id}/upload_package/`, formData }, onProgress);
api.bookSourceImport = formData =>
  uploadFile({ url: '/api/legado/book-sources/import_json/', formData });

export default api;
